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

import static com.google.common.base.Preconditions.checkState;
import static com.linecorp.armeria.common.util.UnmodifiableFuture.completedFuture;
import static com.linecorp.armeria.common.util.UnmodifiableFuture.exceptionallyCompletedFuture;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.annotation.Nullable;

/**
 * Provides asynchronous start-stop life cycle support.
 *
 * @param <T> the type of the startup argument. Use {@link Void} if unused.
 * @param <U> the type of the shutdown argument. Use {@link Void} if unused.
 * @param <V> the type of the startup result. Use {@link Void} if unused.
 * @param <L> the type of the life cycle event listener. Use {@link Void} if unused.
 */
public abstract class StartStopSupport<T, U, V, L> implements ListenableAsyncCloseable {

    private static final Logger logger = LoggerFactory.getLogger(StartStopSupport.class);

    enum State {
        STARTING,
        STARTED,
        STOPPING,
        STOPPED
    }

    private final Executor executor;
    private final List<L> listeners = new CopyOnWriteArrayList<>();
    private final AsyncCloseableSupport closeable = AsyncCloseableSupport.of(this::closeAsync);

    private volatile State state = State.STOPPED;

    /**
     * This future is {@code V}-typed when STARTING/STARTED and {@link Void}-typed when STOPPING/STOPPED.
     */
    private UnmodifiableFuture<?> future = completedFuture(null);

    /**
     * Creates a new instance.
     *
     * @param executor the {@link Executor} which will be used for invoking the extension points of this class:
     *                 <ul>
     *                   <li>{@link #doStart(Object)}</li>
     *                   <li>{@link #doStop(Object)}</li>
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
     * Begins the startup procedure without an argument by calling {@link #doStart(Object)}, ensuring that
     * neither {@link #doStart(Object)} nor {@link #doStop(Object)} is invoked concurrently. When the startup
     * fails, {@link #stop()} will be invoked automatically to roll back the side effect caused by this method
     * and any exceptions that occurred during the rollback will be reported to
     * {@link #rollbackFailed(Throwable)}. This method is a shortcut for
     * {@code start(null, null, failIfStarted)}.
     *
     * @param failIfStarted whether to fail the returned {@link CompletableFuture} with
     *                      an {@link IllegalStateException} when the startup procedure is already
     *                      in progress or done
     */
    public final CompletableFuture<V> start(boolean failIfStarted) {
        return start(null, null, failIfStarted);
    }

    /**
     * Begins the startup procedure without an argument by calling {@link #doStart(Object)}, ensuring that
     * neither {@link #doStart(Object)} nor {@link #doStop(Object)} is invoked concurrently. When the startup
     * fails, {@link #stop()} will be invoked automatically to roll back the side effect caused by this method
     * and any exceptions that occurred during the rollback will be reported to
     * {@link #rollbackFailed(Throwable)}. This method is a shortcut for
     * {@code start(arg, null, failIfStarted)}.
     *
     * @param arg           the argument to pass to {@link #doStart(Object)},
     *                      or {@code null} to pass no argument.
     * @param failIfStarted whether to fail the returned {@link CompletableFuture} with
     *                      an {@link IllegalStateException} when the startup procedure is already
     *                      in progress or done
     */
    public final CompletableFuture<V> start(@Nullable T arg, boolean failIfStarted) {
        return start(arg, null, failIfStarted);
    }

    /**
     * Begins the startup procedure by calling {@link #doStart(Object)}, ensuring that neither
     * {@link #doStart(Object)} nor {@link #doStop(Object)} is invoked concurrently. When the startup fails,
     * {@link #stop(Object)} will be invoked with the specified {@code rollbackArg} automatically to roll back
     * the side effect caused by this method and any exceptions that occurred during the rollback will be
     * reported to {@link #rollbackFailed(Throwable)}.
     *
     * @param arg           the argument to pass to {@link #doStart(Object)},
     *                      or {@code null} to pass no argument.
     * @param rollbackArg   the argument to pass to {@link #doStop(Object)} when rolling back.
     * @param failIfStarted whether to fail the returned {@link CompletableFuture} with
     *                      an {@link IllegalStateException} when the startup procedure is already
     *                      in progress or done
     */
    public final CompletableFuture<V> start(@Nullable T arg, @Nullable U rollbackArg, boolean failIfStarted) {
        return start0(arg, rollbackArg, failIfStarted);
    }

    private synchronized UnmodifiableFuture<V> start0(@Nullable T arg,
                                                      @Nullable U rollbackArg,
                                                      boolean failIfStarted) {
        if (closeable.isClosing()) {
            return exceptionallyCompletedFuture(new IllegalStateException("closed already"));
        }

        switch (state) {
            case STARTING:
            case STARTED:
                if (failIfStarted) {
                    return exceptionallyCompletedFuture(
                            new IllegalStateException("must be stopped to start; currently " + state));
                } else {
                    @SuppressWarnings("unchecked")
                    final UnmodifiableFuture<V> castFuture = (UnmodifiableFuture<V>) future;
                    return castFuture;
                }
            case STOPPING:
                // A user called start() to restart, but not stopped completely yet.
                // Try again once stopped.
                return UnmodifiableFuture.wrap(
                        future.exceptionally(unused -> null)
                              .thenComposeAsync(unused -> start(arg, failIfStarted), executor));
        }

        assert state == State.STOPPED : "state: " + state;
        state = State.STARTING;

        // Attempt to start.
        final CompletableFuture<V> startFuture = new CompletableFuture<>();
        boolean submitted = false;
        try {
            executor.execute(() -> {
                try {
                    notifyListeners(State.STARTING, arg, null, null);
                    final CompletionStage<V> f = doStart(arg);
                    checkState(f != null, "doStart() returned null.");

                    f.handle((result, cause) -> {
                        if (cause != null) {
                            startFuture.completeExceptionally(cause);
                        } else {
                            startFuture.complete(result);
                        }
                        return null;
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

        final UnmodifiableFuture<V> future = UnmodifiableFuture.wrap(
                startFuture.handleAsync((result, cause) -> {
                    if (cause != null) {
                        // Failed to start. Stop and complete with the start failure cause.
                        final CompletableFuture<Void> rollbackFuture =
                                stop(rollbackArg, true).exceptionally(stopCause -> {
                                    rollbackFailed(Exceptions.peel(stopCause));
                                    return null;
                                });

                        return rollbackFuture.<V>thenCompose(unused -> exceptionallyCompletedFuture(cause));
                    } else {
                        enter(State.STARTED, arg, null, result);
                        return completedFuture(result);
                    }
                }, executor).thenCompose(Function.identity()));

        this.future = future;
        return future;
    }

    /**
     * Begins the shutdown procedure without an argument by calling {@link #doStop(Object)}, ensuring that
     * neither {@link #doStart(Object)} nor {@link #doStop(Object)} is invoked concurrently. This method is
     * a shortcut of {@code stop(null)}.
     */
    public final CompletableFuture<Void> stop() {
        return stop(null);
    }

    /**
     * Begins the shutdown procedure by calling {@link #doStop(Object)}, ensuring that neither
     * {@link #doStart(Object)} nor {@link #doStop(Object)} is invoked concurrently.
     *
     * @param arg the argument to pass to {@link #doStop(Object)}, or {@code null} to pass no argument.
     */
    public final CompletableFuture<Void> stop(@Nullable U arg) {
        return stop(arg, false);
    }

    private CompletableFuture<Void> stop(@Nullable U arg, boolean rollback) {
        return stop0(arg, rollback);
    }

    private synchronized UnmodifiableFuture<Void> stop0(@Nullable U arg, boolean rollback) {
        switch (state) {
            case STARTING:
                if (!rollback) {
                    // Try again once started.
                    return UnmodifiableFuture.wrap(
                            future.exceptionally(unused -> null) // Ignore the exception.
                                  .thenComposeAsync(unused -> stop(arg), executor));
                } else {
                    break;
                }
            case STOPPING:
            case STOPPED:
                @SuppressWarnings("unchecked")
                final UnmodifiableFuture<Void> castFuture =
                        (UnmodifiableFuture<Void>) future;
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
                    notifyListeners(State.STOPPING, null, arg, null);
                    final CompletionStage<Void> f = doStop(arg);
                    checkState(f != null, "doStop() returned null.");

                    f.handle((unused, cause) -> {
                        if (cause != null) {
                            stopFuture.completeExceptionally(cause);
                        } else {
                            stopFuture.complete(null);
                        }
                        return null;
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

        final UnmodifiableFuture<Void> future = UnmodifiableFuture.wrap(
                stopFuture.whenCompleteAsync((unused1, cause) -> enter(State.STOPPED, null, arg, null),
                                             executor));
        this.future = future;
        return future;
    }

    @Override
    public final boolean isClosing() {
        return closeable.isClosing();
    }

    @Override
    public final boolean isClosed() {
        return closeable.isClosed();
    }

    @Override
    public final CompletableFuture<?> whenClosed() {
        return closeable.whenClosed();
    }

    @Override
    public final CompletableFuture<?> closeAsync() {
        return closeable.closeAsync();
    }

    private void closeAsync(CompletableFuture<?> future) {
        stop(null).handle((result, cause) -> {
            if (cause != null) {
                future.completeExceptionally(cause);
            } else {
                future.complete(null);
            }
            return null;
        });
    }

    /**
     * A synchronous version of {@link #stop(Object)}. Exceptions occurred during shutdown are reported to
     * {@link #closeFailed(Throwable)}. No argument (i.e. {@code null}) is passed.
     */
    @Override
    public final void close() {
        try {
            closeable.close();
        } catch (Throwable e) {
            closeFailed(Exceptions.peel(e));
        }
    }

    private void enter(State state, @Nullable T startArg, @Nullable U stopArg, @Nullable V startResult) {
        synchronized (this) {
            assert this.state != state : "transition to the same state: " + state;
            this.state = state;
        }
        notifyListeners(state, startArg, stopArg, startResult);
    }

    private void notifyListeners(State state, @Nullable T startArg, @Nullable U stopArg,
                                 @Nullable V startResult) {
        for (L l : listeners) {
            try {
                switch (state) {
                    case STARTING:
                        notifyStarting(l, startArg);
                        break;
                    case STARTED:
                        notifyStarted(l, startArg, startResult);
                        break;
                    case STOPPING:
                        notifyStopping(l, stopArg);
                        break;
                    case STOPPED:
                        notifyStopped(l, stopArg);
                        break;
                    default:
                        throw new Error("unknown state: " + state);
                }
            } catch (Exception cause) {
                notificationFailed(l, cause);

                // Propagate the exception if notifyStarting throws an exception.
                if (state == State.STARTING) {
                    throw new IllegalStateException("Failed to start: " + cause, cause);
                }
            }
        }
    }

    /**
     * Invoked by {@link #start(Object, boolean)} to perform the actual startup.
     *
     * @param arg the argument passed from {@link #start(Object, boolean)},
     *            or {@code null} if no argument was specified.
     */
    protected abstract CompletionStage<V> doStart(@Nullable T arg) throws Exception;

    /**
     * Invoked by {@link #stop(Object)} to perform the actual startup, or indirectly by
     * {@link #start(Object, boolean)} when startup failed.
     *
     * @param arg the argument passed from {@link #stop(Object)},
     *            or {@code null} if no argument was specified.
     */
    protected abstract CompletionStage<Void> doStop(@Nullable U arg) throws Exception;

    /**
     * Invoked when the startup procedure begins.
     * Note that the startup procedure will be aborted if an exception is thrown.
     *
     * @param listener the listener
     * @param arg      the argument passed from {@link #start(Object, boolean)},
     *                 or {@code null} if no argument was specified.
     */
    protected void notifyStarting(L listener, @Nullable T arg) throws Exception {}

    /**
     * Invoked when the startup procedure is finished.
     *
     * @param listener the listener
     * @param arg      the argument passed from {@link #start(Object, boolean)},
     *                 or {@code null} if no argument was specified.
     * @param result   the value of the {@link CompletionStage} returned by {@link #doStart(Object)}.
     */
    protected void notifyStarted(L listener, @Nullable T arg, @Nullable V result) throws Exception {}

    /**
     * Invoked when the shutdown procedure begins.
     *
     * @param listener the listener
     * @param arg      the argument passed from {@link #stop(Object)},
     *                 or {@code null} if no argument was specified.
     */
    protected void notifyStopping(L listener, @Nullable U arg) throws Exception {}

    /**
     * Invoked when the shutdown procedure is finished.
     *
     * @param listener the listener
     * @param arg      the argument passed from {@link #stop(Object)},
     *                 or {@code null} if no argument was specified.
     */
    protected void notifyStopped(L listener, @Nullable U arg) throws Exception {}

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
