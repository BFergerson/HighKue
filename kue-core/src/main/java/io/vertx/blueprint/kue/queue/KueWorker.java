package io.vertx.blueprint.kue.queue;

import io.vertx.blueprint.kue.Kue;
import io.vertx.blueprint.kue.util.RedisHelper;
import io.vertx.core.*;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.redis.client.RedisAPI;
import io.vertx.redis.client.RedisConnection;
import io.vertx.redis.client.ResponseType;

import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

/**
 * The verticle for processing Kue tasks.
 *
 * @author Eric Zhao
 */
public class KueWorker extends AbstractVerticle {

    private static final Logger logger = LoggerFactory.getLogger(KueWorker.class);

    private final Kue kue;
    private RedisConnection localClient; //todo: remove?
    private RedisAPI client; // Every worker use different clients.
    private EventBus eventBus;
    private Job job;
    private final String type;
    private final Handler<Job> jobHandler;

    private MessageConsumer<JsonObject> doneConsumer; // Preserve for unregister the consumer.
    private MessageConsumer<String> doneFailConsumer;

    public KueWorker(String type, Handler<Job> jobHandler, Kue kue) {
        this.type = type;
        this.jobHandler = jobHandler;
        this.kue = kue;
    }

    @Override
    public void start(Promise<Void> startFuture) {
        this.eventBus = vertx.eventBus();
        RedisHelper.client(vertx, config()).connect(it -> {
            if (it.succeeded()) {
                this.localClient = it.result();
                this.client = RedisAPI.api(localClient);
                prepareAndStart();

                startFuture.complete();
            } else {
                startFuture.fail(it.cause());
            }
        });
    }

    /**
     * Prepare job and start processing procedure.
     */
    private void prepareAndStart() {
        cleanup();
        getJobFromBackend().onComplete(jr -> {
            if (jr.succeeded()) {
                if (jr.result().isPresent()) {
                    job = jr.result().get();
                    job.setLocalClient(client);

                    logger.info("Got job from backend. Job id: " + job.getId());
                    process();
                } else {
                    emitJobEvent("error", null, new JsonObject().put("message", "job_not_exist"));
                    throw new IllegalStateException("job not exist");
                }
            } else {
                emitJobEvent("error", null, new JsonObject().put("message", jr.cause().getMessage()));
                jr.cause().printStackTrace();
            }
        });
    }

    /**
     * Process the job.
     */
    private void process() {
        logger.info("Processing job. Job id: " + job.getId());
        Context vertxContext = vertx.getOrCreateContext();
        long curTime = System.currentTimeMillis();
        this.job.setStarted_at(curTime)
                .set("started_at", String.valueOf(curTime))
                .compose(Job::active)
                .onComplete(r -> {
                    if (r.succeeded()) {
                        Job j = r.result();
                        // emit start event
                        this.emitJobEvent("start", j, null);

                        logger.debug("KueWorker::process[instance:Verticle(" + this.deploymentID() + ")] with job " + job.getId());
                        // process logic invocation
                        try {
                            vertxContext.runOnContext(it -> {
                                logger.info("Executing job user-logic. Job id: " + job.getId());
                                jobHandler.handle(j);
                            });
                        } catch (Exception ex) {
                            j.done(ex);
                        }

                        // subscribe the job done event
                        doneConsumer = eventBus.consumer(Kue.workerAddress("done", j), msg -> {
                            createDoneCallback(j).handle(Future.succeededFuture(msg.body().getJsonObject("result")));
                        });
                        doneFailConsumer = eventBus.consumer(Kue.workerAddress("done_fail", j), msg -> {
                            createDoneCallback(j).handle(Future.failedFuture(msg.body()));
                        });
                    } else {
                        this.emitJobEvent("error", this.job, new JsonObject().put("message", r.cause().getMessage()));
                        r.cause().printStackTrace();
                    }
                });
    }

    private void cleanup() {
        Optional.ofNullable(doneConsumer).ifPresent(MessageConsumer::unregister);
        Optional.ofNullable(doneFailConsumer).ifPresent(MessageConsumer::unregister);
        this.job = null;
    }

    private void error(Throwable ex, Job job) {
        JsonObject err = new JsonObject().put("message", ex.getMessage())
                .put("id", job.getId());
        eventBus.send(Kue.workerAddress("error"), err);
    }

    private void fail(Throwable ex) {
        job.failedAttempt(ex).onComplete(r -> {
            if (r.failed()) {
                this.error(r.cause(), job);
            } else {
                Job res = r.result();
                if (res.hasAttempts()) {
                    this.emitJobEvent("failed_attempt", job, new JsonObject().put("message", ex.getMessage())); // shouldn't include err?
                } else {
                    this.emitJobEvent("failed", job, new JsonObject().put("message", ex.getMessage()));
                }
            }
            prepareAndStart();
        });
    }

    /**
     * Redis zpop atomic primitive with transaction.
     *
     * @param key redis key
     * @return the async result of zpop
     */
    private Future<Long> zpop(String key) {
        Promise<Long> promise = Promise.promise();
        client.zpopmin(Collections.singletonList(key), r -> {
            if (r.succeeded()) {
                JsonArray res = new JsonArray();
                r.result().forEach(it -> {
                    if (it.type() == ResponseType.MULTI) {
                        JsonArray innerArray = new JsonArray();
                        it.forEach(it2 -> {
                            innerArray.add(it2.toString());
                        });
                        res.add(innerArray);
                    } else {
                        res.add(it.toString());
                    }
                });
                try {
                    promise.complete(Long.parseLong(RedisHelper.stripFIFO(res.getString(0))));
                } catch (Exception ex) {
                    promise.fail(ex);
                }
            } else {
                promise.fail(r.cause());
            }
        });
        return promise.future();
    }

    /**
     * Get a job from Redis backend by priority.
     *
     * @return async result of job
     */
    private Future<Optional<Job>> getJobFromBackend() {
        logger.debug("Getting job from backend");
        Promise<Optional<Job>> promise = Promise.promise();
        client.blpop(Arrays.asList(RedisHelper.getKey(String.format("%s:jobs", type)), "0"), r1 -> {
            if (r1.failed()) {
                if (!kue.isClosed()) {
                    client.lpush(Arrays.asList(RedisHelper.getKey(String.format("%s:jobs", type)), "1"), r2 -> {
                        if (r2.failed()) {
                            promise.fail(r2.cause());
                        }
                    });
                } else {
                    logger.info("Prematurely ended looking for backend jobs due to Kue closing");
                }
            } else {
                zpop(RedisHelper.getKey(String.format("jobs:%s:INACTIVE", type)))
                        .compose(kue::getJob)
                        .onComplete(r -> {
                            if (r.succeeded()) {
                                logger.debug("Successfully fetched job from backend");
                                promise.complete(r.result());
                            } else {
                                logger.error("Failed to fetch job from backend", r.cause());
                                promise.fail(r.cause());
                            }
                        });
            }
        });
        return promise.future();
    }

    private Handler<AsyncResult<JsonObject>> createDoneCallback(Job job) {
        return r0 -> {
            if (job == null) {
                // maybe should warn
                return;
            }
            if (r0.failed()) {
                this.fail(r0.cause());
                return;
            }
            long dur = System.currentTimeMillis() - job.getStarted_at();
            job.setDuration(dur)
                    .set("duration", String.valueOf(dur));
            JsonObject result = r0.result();
            if (result != null) {
                job.setResult(result)
                        .set("result", result.encodePrettily());
            }

            job.complete().onComplete(r -> {
                if (r.succeeded()) {
                    Job j = r.result();
                    if (j.isRemoveOnComplete()) {
                        j.remove();
                    }
                    this.emitJobEvent("complete", j, null);

                    this.prepareAndStart(); // prepare for next job
                }
            });
        };
    }

    @Override
    public void stop() {
        // stop hook
        cleanup();
    }

    /**
     * Emit job event.
     *
     * @param event event type
     * @param job   corresponding job
     * @param extra extra data
     */
    private void emitJobEvent(String event, Job job, JsonObject extra) {
        JsonObject data = new JsonObject().put("extra", extra);
        if (job != null) {
            data.put("job", job.toJson());
        }
        eventBus.send(Kue.workerAddress("job_" + event), data);
        switch (event) {
            case "failed":
            case "failed_attempt":
                eventBus.send(Kue.getCertainJobAddress(event, job), data);
                break;
            case "error":
                eventBus.send(Kue.workerAddress("error"), data);
                break;
            default:
                eventBus.send(Kue.getCertainJobAddress(event, job), job.toJson());
        }
    }
}
