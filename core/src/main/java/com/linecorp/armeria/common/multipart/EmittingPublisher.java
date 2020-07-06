/*
 * Copyright 2020 LINE Corporation
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
/*
 * Copyright (c)  2020 Oracle and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.linecorp.armeria.common.multipart;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import javax.annotation.Nullable;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/**
 * Emitting publisher for manual publishing on the same thread.
 * {@link EmittingPublisher} doesn't have any buffering capability and propagates backpressure
 * directly by returning {@code false} from {@link EmittingPublisher#emit(Object)} in case there
 * is no demand, or {@code cancel} signal has been received.
 * <p>
 * For publishing with buffering in case of backpressure use {@link BufferedEmittingPublisher}.
 * </p>
 *
 * <p>
 * <strong>This publisher allows only a single subscriber</strong>.
 * </p>
 *
 * @param <T> type of emitted item
 */
final class EmittingPublisher<T> implements Publisher<T> {

    // Forked from https://github.com/oracle/helidon/blob/1e05a7ab9c50730185f5a5c4dc9d5ab3c6337601/common/reactive/src/main/java/io/helidon/common/reactive/EmittingPublisher.java

    private final AtomicReference<State> state = new AtomicReference<>(State.INIT);
    private final AtomicLong requested = new AtomicLong();
    private final AtomicBoolean subscribed = new AtomicBoolean(false);
    private final CompletableFuture<Void> deferredComplete = new CompletableFuture<>();

    @Nullable
    private volatile Subscriber<? super T> subscriber;
    @Nullable
    private BiConsumer<Long, Long> requestCallback;
    private Runnable onSubscribeCallback = () -> {};
    private Runnable cancelCallback = () -> {};

    @Override
    public void subscribe(final Subscriber<? super T> subscriber) {
        requireNonNull(subscriber, "subscriber");

        if (!subscribed.compareAndSet(false, true)) {
            subscriber.onSubscribe(SubscriptionHelper.CANCELED);
            subscriber.onError(new IllegalStateException("Only single subscriber is allowed!"));
            return;
        }

        this.subscriber = subscriber;

        subscriber.onSubscribe(new Subscription() {
            @Override
            public void request(final long n) {
                if (state.get().isTerminated()) {
                    return;
                }
                if (n < 1) {
                    fail(new IllegalArgumentException(
                            "Rule §3.9 violated: non-positive request amount is forbidden"));
                    return;
                }
                requested.updateAndGet(r -> Long.MAX_VALUE - r > n ? n + r : Long.MAX_VALUE);
                state.compareAndSet(State.INIT, State.REQUESTED);
                if (state.updateAndGet(s -> s == State.SUBSCRIBED ? State.READY_TO_EMIT : s) ==
                    State.READY_TO_EMIT) {
                    if (requestCallback != null) {
                        requestCallback.accept(n, requested.get());
                    }
                }
            }

            @Override
            public void cancel() {
                if (state.getAndUpdate(s -> s != State.COMPLETED && s != State.FAILED ? State.CANCELLED : s) !=
                    State.CANCELLED) {
                    cancelCallback.run();
                    EmittingPublisher.this.subscriber = null;
                }
            }
        });
        state.compareAndSet(State.INIT, State.SUBSCRIBED);
        if (state.compareAndSet(State.REQUESTED, State.READY_TO_EMIT)) {
            if (requestCallback != null) {
                //defer request signals until we are out of onSubscribe
                requestCallback.accept(requested.get(), requested.get());
            }
        }

        deferredComplete.complete(null);
        onSubscribeCallback.run();
    }

    /**
     * Properly fail the stream, set publisher to cancelled state and send {@code onError} signal downstream.
     * Signal {@code onError} is sent only once, any other call to this method is no-op.
     *
     * @param throwable Sent as {@code onError} signal
     */
    void fail(Throwable throwable) {
        if (deferredComplete.isDone()) {
            signalOnError(throwable);
        } else {
            deferredComplete.thenRun(() -> signalOnError(throwable));
        }
    }

    /**
     * Properly complete the stream, set publisher to completed state and send {@code onComplete} signal
     * downstream.
     * Signal {@code onComplete} is sent only once, any other call to this method is no-op.
     */
    void complete() {
        deferredComplete.thenRun(this::signalOnComplete);
    }

    private void signalOnError(Throwable throwable) {
        synchronized (this) {
            try {
                final Subscriber<? super T> subscriber = this.subscriber;
                if (subscriber == null) {
                    // cancel released the reference already
                    return;
                }
                if (state.compareAndSet(State.INIT, State.FAILED) ||
                    state.compareAndSet(State.SUBSCRIBED, State.FAILED) ||
                    state.compareAndSet(State.REQUESTED, State.FAILED) ||
                    state.compareAndSet(State.READY_TO_EMIT, State.FAILED)) {
                    this.subscriber = null;
                    subscriber.onError(throwable);
                }
            } catch (Throwable t) {
                throw new IllegalStateException("On error threw an exception!", t);
            }
        }
    }

    private void signalOnComplete() {
        synchronized (this) {
            final Subscriber<? super T> subscriber = this.subscriber;
            if (subscriber == null) {
                // cancel released the reference already
                return;
            }
            if (state.compareAndSet(State.INIT, State.COMPLETED) ||
                state.compareAndSet(State.SUBSCRIBED, State.COMPLETED) ||
                state.compareAndSet(State.REQUESTED, State.COMPLETED) ||
                state.compareAndSet(State.READY_TO_EMIT, State.COMPLETED)) {
                this.subscriber = null;
                subscriber.onComplete();
            }
        }
    }

    /**
     * Emits one item to the stream, if there is enough requested and publisher is not cancelled,
     * item is signaled to downstream as {@code onNext} and method returns true.
     * If there is requested less than 1, nothing is sent and method returns false.
     *
     * @param item to be sent downstream
     * @return true if item successfully sent, false if canceled or no demand
     * @throws IllegalStateException if publisher is completed
     */
    boolean emit(T item) {
        return state.get().emit(this, item);
    }

    /**
     * Checks if demand is higher than 0. Returned value should be used
     * as informative and can change asynchronously.
     *
     * @return true if so
     */
    boolean hasRequests() {
        return this.requested.get() > 0;
    }

    /**
     * Checks if downstream requested unbounded number of items, eg. there is no backpressure.
     *
     * @return true if so
     */
    boolean isUnbounded() {
        return state.get() == State.READY_TO_EMIT && unbounded();
    }

    /**
     * Executed when request signal from downstream arrive.
     *
     * @param onSubscribeCallback to be executed
     */
    void onSubscribe(Runnable onSubscribeCallback) {
        final Runnable first = this.onSubscribeCallback;
        this.onSubscribeCallback = () -> {
            first.run();
            onSubscribeCallback.run();
        };
    }

    /**
     * Executed when cancel signal from downstream arrive.
     *
     * @param cancelCallback to be executed
     */
    void onCancel(Runnable cancelCallback) {
        final Runnable first = this.cancelCallback;
        this.cancelCallback = () -> {
            first.run();
            cancelCallback.run();
        };
    }

    /**
     * Callback executed when request signal from downstream arrive.
     * <ul>
     * <li><b>param</b> {@code n} the requested count.</li>
     * <li><b>param</b> {@code result} the current total cumulative requested count, ranges between [0,
     * {@link Long#MAX_VALUE}]
     * where the max indicates that this publisher is unbounded.</li>
     * </ul>
     *
     * @param requestCallback to be executed
     */
    void onRequest(BiConsumer<Long, Long> requestCallback) {
        if (this.requestCallback == null) {
            this.requestCallback = requestCallback;
        } else {
            final BiConsumer<Long, Long> first = this.requestCallback;
            this.requestCallback = (n, result) -> {
                first.accept(n, result);
                requestCallback.accept(n, result);
            };
        }
    }

    private boolean unbounded() {
        return requested.get() == Long.MAX_VALUE;
    }

    private boolean internalEmit(T item) {
        final Throwable error;
        synchronized (this) {
            try {
                //State could have changed before acquiring the lock
                if (State.READY_TO_EMIT != state.get()) {
                    return false;
                }
                final Subscriber<? super T> subscriber = this.subscriber;
                if (subscriber == null) {
                    // cancel released the reference
                    return false;
                }
                if (requested.getAndUpdate(r -> r > 0 ? r != Long.MAX_VALUE ? r - 1 : Long.MAX_VALUE : 0) < 1) {
                    // there is a chance racing request will increment counter between this check and onNext
                    // lets delegate that to emit caller
                    return false;
                }
                subscriber.onNext(item);
                return true;
            } catch (NullPointerException npe) {
                throw npe;
            } catch (Throwable t) {
                error = t;
            }
        }
        fail(new IllegalStateException(error));
        return false;
    }

    private enum State {
        INIT {
            @Override
            <T> boolean emit(EmittingPublisher<T> publisher, T item) {
                return false;
            }

            @Override
            boolean isTerminated() {
                return false;
            }
        },
        REQUESTED {
            @Override
            <T> boolean emit(EmittingPublisher<T> publisher, T item) {
                return false;
            }

            @Override
            boolean isTerminated() {
                return false;
            }
        },
        SUBSCRIBED {
            @Override
            <T> boolean emit(EmittingPublisher<T> publisher, T item) {
                return false;
            }

            @Override
            boolean isTerminated() {
                return false;
            }
        },
        READY_TO_EMIT {
            @Override
            <T> boolean emit(EmittingPublisher<T> publisher, T item) {
                return publisher.internalEmit(item);
            }

            @Override
            boolean isTerminated() {
                return false;
            }
        },
        CANCELLED {
            @Override
            <T> boolean emit(EmittingPublisher<T> publisher, T item) {
                return false;
            }

            @Override
            boolean isTerminated() {
                return true;
            }
        },
        FAILED {
            @Override
            <T> boolean emit(EmittingPublisher<T> publisher, T item) {
                return false;
            }

            @Override
            boolean isTerminated() {
                return true;
            }
        },
        COMPLETED {
            @Override
            <T> boolean emit(EmittingPublisher<T> publisher, T item) {
                throw new IllegalStateException("Emitter is completed!");
            }

            @Override
            boolean isTerminated() {
                return true;
            }
        };

        abstract <T> boolean emit(EmittingPublisher<T> publisher, T item);

        abstract boolean isTerminated();
    }
}
