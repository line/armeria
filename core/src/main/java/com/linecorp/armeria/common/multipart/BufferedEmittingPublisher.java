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
 */

package com.linecorp.armeria.common.multipart;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;

/**
 * Emitting publisher for manual publishing with built-in buffer for handling backpressure.
 *
 * <p>
 * <strong>This publisher allows only a single subscriber</strong>.
 * </p>
 *
 * @param <T> type of emitted item
 */
final class BufferedEmittingPublisher<T> implements Publisher<T> {

    // Forked from https://github.com/oracle/helidon/blob/83d3159729fb9366f93408c45eeb8d11b9f00294/common/reactive/src/main/java/io/helidon/common/reactive/BufferedEmittingPublisher.java

    // TODO(ikhoon): Use Atomic*FieldUpdater
    private final AtomicReference<State> state = new AtomicReference<>(State.READY_TO_EMIT);
    private final ConcurrentLinkedQueue<T> buffer = new ConcurrentLinkedQueue<>();
    private final EmittingPublisher<T> emitter = new EmittingPublisher<>();
    private final AtomicLong deferredDrains = new AtomicLong(0);
    private final AtomicBoolean draining = new AtomicBoolean(false);
    private final AtomicReference<Throwable> error = new AtomicReference<>();

    @Nullable
    private BiConsumer<Long, Long> requestCallback;
    @Nullable
    private Consumer<? super T> onEmitCallback;
    private boolean safeToSkipBuffer;

    @Override
    public void subscribe(final Subscriber<? super T> subscriber) {
        emitter.onSubscribe(() -> state.get().drain(this));
        emitter.onRequest((n, cnt) -> {
            if (requestCallback != null) {
                requestCallback.accept(n, cnt);
            }
            state.get().drain(this);
        });
        emitter.onCancel(() -> state.compareAndSet(State.READY_TO_EMIT, State.CANCELLED));
        emitter.subscribe(subscriber);
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

    /**
     * Callback executed right after {@code onNext} is actually sent.
     * <ul>
     * <li><b>param</b> {@code i} sent item</li>
     * </ul>
     *
     * @param onEmitCallback to be executed
     */
    void onEmit(Consumer<T> onEmitCallback) {
        if (this.onEmitCallback == null) {
            this.onEmitCallback = onEmitCallback;
        } else {
            final Consumer<? super T> first = this.onEmitCallback;
            this.onEmitCallback = e -> {
                first.accept(e);
                onEmitCallback.accept(e);
            };
        }
    }

    /**
     * Emits item to the stream, if there is no immediate demand from downstream,
     * buffer item for sending when demand is signaled.
     *
     * @param item to be emitted
     * @return actual size of the buffer, value should be used as informative and can change asynchronously
     * @throws IllegalStateException if cancelled, completed of failed
     */
    int emit(final T item) {
        return state.get().emit(this, item);
    }

    /**
     * Sends {@code onError} signal downstream, regardless of the buffer content.
     * Nothing else can be sent downstream after calling fail.
     * {@link BufferedEmittingPublisher#emit(Object)} throws {@link IllegalStateException} after calling fail.
     *
     * @param throwable Throwable to be sent downstream as onError signal.
     */
    void fail(Throwable throwable) {
        error.set(throwable);
        if (state.compareAndSet(State.READY_TO_EMIT, State.FAILED)) {
            emitter.fail(throwable);
        }
    }

    /**
     * Drains the buffer, in case of not sufficient demands wait for more requests,
     * then send {@code onComplete} signal to downstream.
     * {@link BufferedEmittingPublisher#emit(Object)} throws {@link IllegalStateException} after calling
     * complete.
     */
    void complete() {
        if (state.compareAndSet(State.READY_TO_EMIT, State.COMPLETING)) {
            //drain buffer then complete
            State.READY_TO_EMIT.drain(this);
        }
    }

    /**
     * Clears whole buffer, invoke consumer for each item before discarding it.
     *
     * @param consumer to be invoked for each item
     */
    public void clearBuffer(Consumer<T> consumer) {
        while (!buffer.isEmpty()) {
            consumer.accept(buffer.poll());
        }
    }

    /**
     * Checks if downstream requested unbounded number of items eg. {@code Long.MAX_VALUE}.
     *
     * @return true if so
     */
    boolean isUnbounded() {
        return emitter.isUnbounded();
    }

    /**
     * Checks if demand is higher than 0.
     * Returned value should be used as informative and can change asynchronously.
     *
     * @return true if demand is higher than 0
     */
    public boolean hasRequests() {
        return emitter.hasRequests();
    }

    /**
     * Checks if publisher is in terminal {@link State#CANCELLED}.
     *
     * @return true if so
     */
    public boolean isCancelled() {
        return state.get() == State.CANCELLED;
    }

    private void drainBuffer() {
        deferredDrains.incrementAndGet();

        long drains;
        do {
            if (draining.getAndSet(true)) {
                //other thread already draining
                return;
            }
            drains = deferredDrains.getAndUpdate(d -> d == 0 ? 0 : d - 1);
            if (drains > 0) {
                // in case of parallel drains invoked by request
                // increasing demand during draining
                actualDrain();
                drains--;
            }
            draining.set(false);
            // changed while draining, try again
        } while (drains < deferredDrains.get());
    }

    private void actualDrain() {
        while (!buffer.isEmpty()) {
            if (emitter.emit(buffer.peek())) {
                if (onEmitCallback != null) {
                    onEmitCallback.accept(buffer.poll());
                } else {
                    buffer.poll();
                }
            } else {
                break;
            }
        }
        if (buffer.isEmpty() && state.compareAndSet(State.COMPLETING, State.COMPLETED)) {
            // Buffer drained, time for complete
            emitter.complete();
        }
    }

    private int emitOrBuffer(T item) {
        synchronized (this) {
            try {
                if (buffer.isEmpty() && emitter.emit(item)) {
                    // Buffer drained, emit successful
                    // saved time by skipping buffer
                    if (onEmitCallback != null) {
                        onEmitCallback.accept(item);
                    }
                    return 0;
                } else {
                    // safe slower path thru buffer
                    buffer.add(item);
                    state.get().drain(this);
                    return buffer.size();
                }
            } finally {
                // If unbounded, check only once if buffer is empty
                if (!safeToSkipBuffer && isUnbounded() && buffer.isEmpty()) {
                    safeToSkipBuffer = true;
                }
            }
        }
    }

    private int unboundedEmitOrBuffer(T item) {
        // Not reachable unless unbounded req was made
        // and buffer is empty
        if (emitter.emit(item)) {
            // Emit successful
            if (onEmitCallback != null) {
                onEmitCallback.accept(item);
            }
            return 0;
        } else {
            // Emitter can be only in terminal state
            // buffer for later retrieval by clearBuffer()
            buffer.add(item);
            return buffer.size();
        }
    }

    private enum State {
        READY_TO_EMIT {
            @Override
            <T> int emit(BufferedEmittingPublisher<T> publisher, T item) {
                if (publisher.safeToSkipBuffer) {
                    return publisher.unboundedEmitOrBuffer(item);
                }
                return publisher.emitOrBuffer(item);
            }

            @Override
            <T> void drain(BufferedEmittingPublisher<T> publisher) {
                publisher.drainBuffer();
            }
        },
        CANCELLED {
            @Override
            <T> int emit(BufferedEmittingPublisher<T> publisher, T item) {
                throw new IllegalStateException("Emitter is cancelled!");
            }

            @Override
            <T> void drain(BufferedEmittingPublisher<T> publisher) {
                //noop
            }
        },
        FAILED {
            @Override
            <T> int emit(BufferedEmittingPublisher<T> publisher, T item) {
                throw new IllegalStateException("Emitter is in failed state!");
            }

            @Override
            <T> void drain(BufferedEmittingPublisher<T> publisher) {
                //Can't happen twice, internal emitter keeps the state too
                publisher.emitter.fail(publisher.error.get());
            }
        },
        COMPLETING {
            @Override
            <T> int emit(BufferedEmittingPublisher<T> publisher, T item) {
                throw new IllegalStateException("Emitter is completing!");
            }

            @Override
            <T> void drain(BufferedEmittingPublisher<T> publisher) {
                READY_TO_EMIT.drain(publisher);
            }
        },
        COMPLETED {
            @Override
            <T> int emit(BufferedEmittingPublisher<T> publisher, T item) {
                throw new IllegalStateException("Emitter is completed!");
            }

            @Override
            <T> void drain(BufferedEmittingPublisher<T> publisher) {
                //Can't happen twice, internal emitter keeps the state too
                publisher.emitter.complete();
            }
        };

        abstract <T> int emit(BufferedEmittingPublisher<T> publisher, T item);

        abstract <T> void drain(BufferedEmittingPublisher<T> publisher);
    }
}
