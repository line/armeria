/*
 * Copyright 2018 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.common.util;

import static com.spotify.futures.CompletableFutures.exceptionallyCompletedFuture;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.CompletableFuture.completedFuture;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.function.Function;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides asynchronous start-stop life cycle support.
 *
 * @param <V> the type of the startup result. Use {@link Void} if unused.
 * @param <L> the type of the life cycle event listener. Use {@link Void} if unused.
 */
public abstract class StartStopSupport<V, L> implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(StartStopSupport.class);

    enum State {
        STARTING,
        STARTED,
        STOPPING,
        STOPPED
    }

    private final Executor executor;
    private final List<L> listeners = new CopyOnWriteArrayList<>();
    private volatile State state = State.STOPPED;
    /**
     * The value is {@code V}-typed when STARTING/STARTED and {@link Void}-typed when STOPPING/STOPPED.
     */
    private CompletableFuture<?> future = completedFuture(null);

    /**
     * Creates a new instance.
     *
     * @param executor the {@link Executor} which will be used for invoking the extension points of this class:
     *                 <ul>
     *                   <li>{@link #doStart()}</li>
     *                   <li>{@link #doStop()}</li>
     *                   <li>{@link #rollbackFailed(Throwable)}</li>
     *                   <li>{@link #notificationFailed(Object, Throwable)}</li>
     *                   <li>All listener notifications</li>
     *                 </ul>
     *                 .. except {@link #closeFailed(Throwable)} which is invoked at the caller thread.
     */
    protected StartStopSupport(Executor executor) {
        this.executor = requireNonNull(executor, "executor");
    }

    /**
     * Adds the specified {@code listener}, so that it is notified when the state of this
     * {@link StartStopSupport} changes.
     */
    public final void addListener(L listener) {
        listeners.add(requireNonNull(listener, "listener"));
    }

    /**
     * Removes the specified {@code listener}, so that it is not notified anymore.
     */
    public final boolean removeListener(L listener) {
        return listeners.remove(requireNonNull(listener, "listener"));
    }

    /**
     * Begins the startup procedure by calling {@link #doStart()}, ensuring that neither {@link #doStart()}
     * nor {@link #doStop()} is invoked concurrently. When the startup fails, {@link #stop()} will be
     * invoked automatically to roll back the side effect caused by {@link #start(boolean)} and any exceptions
     * that occurred during the rollback will be reported to {@link #rollbackFailed(Throwable)}.
     *
     * @param failIfStarted whether to fail the returned {@link CompletableFuture} with
     *                      an {@link IllegalStateException} when the startup procedure is already
     *                      in progress or done
     */
    public final synchronized CompletableFuture<V> start(boolean failIfStarted) {
        switch (state) {
            case STARTING:
            case STARTED:
                if (failIfStarted) {
                    return exceptionallyCompletedFuture(
                            new IllegalStateException("must be stopped to start; currently " + state));
                } else {
                    @SuppressWarnings("unchecked")
                    final CompletableFuture<V> castFuture = (CompletableFuture<V>) future;
                    return castFuture;
                }
            case STOPPING:
                // A user called start() to restart, but not stopped completely yet.
                // Try again once stopped.
                return future.exceptionally(unused -> null)
                             .thenComposeAsync(unused -> start(failIfStarted), executor);
        }

        assert state == State.STOPPED : "state: " + state;
        state = State.STARTING;

        // Attempt to start.
        final CompletableFuture<V> startFuture = new CompletableFuture<>();
        boolean submitted = false;
        try {
            executor.execute(() -> {
                try {
                    notifyListeners(State.STARTING, null);
                    final CompletionStage<V> f = doStart();
                    if (f == null) {
                        throw new IllegalStateException("doStart() returned null.");
                    }

                    f.whenComplete((result, cause) -> {
                        if (cause != null) {
                            startFuture.completeExceptionally(cause);
                        } else {
                            startFuture.complete(result);
                        }
                    });
                } catch (Exception e) {
                    startFuture.completeExceptionally(e);
                }
            });
            submitted = true;
        } catch (Exception e) {
            return exceptionallyCompletedFuture(e);
        } finally {
            if (!submitted) {
                state = State.STOPPED;
            }
        }

        final CompletableFuture<V> future = startFuture.handleAsync((result, cause) -> {
            if (cause != null) {
                // Failed to start. Stop and complete with the start failure cause.
                final CompletableFuture<Void> rollbackFuture = stop(true).exceptionally(stopCause -> {
                    rollbackFailed(Exceptions.peel(stopCause));
                    return null;
                });

                return rollbackFuture.<V>thenCompose(unused -> exceptionallyCompletedFuture(cause));
            } else {
                enter(State.STARTED, result);
                return completedFuture(result);
            }
        }, executor).thenCompose(Function.identity());

        this.future = future;
        return future;
    }

    /**
     * Begins the shutdown procedure by calling {@link #doStop()}, ensuring that neither {@link #doStart()} nor
     * {@link #doStop()} is invoked concurrently.
     */
    public final CompletableFuture<Void> stop() {
        return stop(false);
    }

    private synchronized CompletableFuture<Void> stop(boolean rollback) {
        switch (state) {
            case STARTING:
                if (!rollback) {
                    // Try again once started.
                    return future.exceptionally(unused -> null) // Ignore the exception.
                                 .thenComposeAsync(unused -> stop(), executor);
                } else {
                    break;
                }
            case STOPPING:
            case STOPPED:
                @SuppressWarnings("unchecked")
                final CompletableFuture<Void> castFuture = (CompletableFuture<Void>) future;
                return castFuture;
        }

        assert state == State.STARTED || rollback : "state: " + state + ", rollback: " + rollback;
        final State oldState = state;
        state = State.STOPPING;

        final CompletableFuture<Void> stopFuture = new CompletableFuture<>();
        boolean submitted = false;
        try {
            executor.execute(() -> {
                try {
                    notifyListeners(State.STOPPING, null);
                    final CompletionStage<Void> f = doStop();
                    if (f == null) {
                        throw new IllegalStateException("doStop() returned null.");
                    }

                    f.whenComplete((unused, cause) -> {
                        if (cause != null) {
                            stopFuture.completeExceptionally(cause);
                        } else {
                            stopFuture.complete(null);
                        }
                    });
                } catch (Exception e) {
                    stopFuture.completeExceptionally(e);
                }
            });
            submitted = true;
        } catch (Exception e) {
            return exceptionallyCompletedFuture(e);
        } finally {
            if (!submitted) {
                state = oldState;
            }
        }

        final CompletableFuture<Void> future = stopFuture.whenCompleteAsync(
                (unused1, unused2) -> enter(State.STOPPED, null), executor);
        this.future = future;
        return future;
    }

    /**
     * A synchronous version of {@link #stop()}. Exceptions occurred during shutdown are reported to
     * {@link #closeFailed(Throwable)}.
     */
    @Override
    public final void close() {
        final CompletableFuture<Void> f;
        synchronized (this) {
            if (state == State.STOPPED) {
                return;
            }
            f = stop();
        }

        boolean interrupted = false;
        for (;;) {
            try {
                f.get();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            } catch (ExecutionException e) {
                closeFailed(Exceptions.peel(e));
                break;
            }
        }

        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void enter(State state, @Nullable V value) {
        synchronized (this) {
            assert this.state != state : "transition to the same state: " + state;
            this.state = state;
        }
        notifyListeners(state, value);
    }

    private void notifyListeners(State state, @Nullable V value) {
        for (L l : listeners) {
            try {
                switch (state) {
                    case STARTING:
                        notifyStarting(l);
                        break;
                    case STARTED:
                        notifyStarted(l, value);
                        break;
                    case STOPPING:
                        notifyStopping(l);
                        break;
                    case STOPPED:
                        notifyStopped(l);
                        break;
                    default:
                        throw new Error("unknown state: " + state);
                }
            } catch (Exception cause) {
                notificationFailed(l, cause);
            }
        }
    }

    /**
     * Invoked by {@link #start(boolean)} to perform the actual startup.
     */
    protected abstract CompletionStage<V> doStart() throws Exception;

    /**
     * Invoked by {@link #stop()} to perform the actual startup, or indirectly by {@link #start(boolean)} when
     * startup failed.
     */
    protected abstract CompletionStage<Void> doStop() throws Exception;

    /**
     * Invoked when the startup procedure begins.
     *
     * @param listener the listener
     */
    protected void notifyStarting(L listener) throws Exception {}

    /**
     * Invoked when the startup procedure is finished.
     *
     * @param listener the listener
     */
    protected void notifyStarted(L listener, @Nullable V value) throws Exception {}

    /**
     * Invoked when the shutdown procedure begins.
     *
     * @param listener the listener
     */
    protected void notifyStopping(L listener) throws Exception {}

    /**
     * Invoked when the shutdown procedure is finished.
     *
     * @param listener the listener
     */
    protected void notifyStopped(L listener) throws Exception {}

    /**
     * Invoked when failed to stop during the rollback after startup failure.
     */
    protected void rollbackFailed(Throwable cause) {
        logStopFailure(cause);
    }

    /**
     * Invoked when an event listener raises an exception.
     */
    protected void notificationFailed(L listener, Throwable cause) {
        logger.warn("Failed to notify a listener: {}", listener, cause);
    }

    /**
     * Invoked when failed to stop in {@link #close()}.
     */
    protected void closeFailed(Throwable cause) {
        logStopFailure(cause);
    }

    private static void logStopFailure(Throwable cause) {
        logger.warn("Failed to stop: {}", cause.getMessage(), cause);
    }

    @Override
    public String toString() {
        return state.name();
    }
}
