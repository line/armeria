/*
 * Copyright 2023 LINE Corporation
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
 * Copyright 2002-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.linecorp.armeria.spring.web.reactive;

import java.util.function.Function;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

import reactor.core.CoreSubscriber;
import reactor.core.Scannable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Operators;
import reactor.util.context.Context;

/**
 * Given a write function that accepts a source {@code Publisher<T>} to write
 * with and returns {@code Publisher<Void>} for the result, this operator helps
 * to defer the invocation of the write function, until we know if the source
 * publisher will begin publishing without an error. If the first emission is
 * an error, the write function is bypassed, and the error is sent directly
 * through the result publisher. Otherwise the write function is invoked.
 *
 * @author Rossen Stoyanchev
 * @author Stephane Maldini
 * @param <T> the type of element signaled
 * @since 5.0
 */
final class ChannelSendOperator<T> extends Mono<Void> implements Scannable {

    // Forked from https://github.com/spring-projects/spring-framework/blob/1e3099759e2d823b6dd1c0c43895abcbe3e02a12/spring-web/src/main/java/org/springframework/http/server/reactive/ChannelSendOperator.java
    // and modified at L370 for not publishing the cache item before receiving request(n) from the subscriber.
    private final Function<Publisher<T>, Publisher<Void>> writeFunction;

    private final Flux<T> source;

    ChannelSendOperator(Publisher<? extends T> source,
                        Function<Publisher<T>, Publisher<Void>> writeFunction) {
        this.source = Flux.from(source);
        this.writeFunction = writeFunction;
    }

    @Override
    @Nullable
    @SuppressWarnings("rawtypes")
    public Object scanUnsafe(Attr key) {
        if (key == Attr.PREFETCH) {
            return Integer.MAX_VALUE;
        }
        if (key == Attr.PARENT) {
            return source;
        }
        return null;
    }

    @Override
    public void subscribe(CoreSubscriber<? super Void> actual) {
        source.subscribe(new WriteBarrier(actual));
    }

    private enum State {

        /** No emissions from the upstream source yet. */
        NEW,

        /**
         * At least one signal of any kind has been received; we're ready to
         * call the write function and proceed with actual writing.
         */
        FIRST_SIGNAL_RECEIVED,

        /**
         * The write subscriber has subscribed and requested; we're going to
         * emit the cached signals.
         */
        EMITTING_CACHED_SIGNALS,

        /**
         * The write subscriber has subscribed, and cached signals have been
         * emitted to it; we're ready to switch to a simple pass-through mode
         * for all remaining signals.
         **/
        READY_TO_WRITE
    }

    /**
     * A barrier inserted between the write source and the write subscriber
     * (i.e. the HTTP server adapter) that pre-fetches and waits for the first
     * signal before deciding whether to hook in to the write subscriber.
     *
     * <p>Acts as:
     * <ul>
     * <li>Subscriber to the write source.
     * <li>Subscription to the write subscriber.
     * <li>Publisher to the write subscriber.
     * </ul>
     *
     * <p>Also uses {@link WriteCompletionBarrier} to communicate completion
     * and detect cancel signals from the completion subscriber.
     */
    private class WriteBarrier implements CoreSubscriber<T>, Subscription, Publisher<T> {

        /* Bridges signals to and from the completionSubscriber */
        private final WriteCompletionBarrier writeCompletionBarrier;

        /* Upstream write source subscription */
        @Nullable
        private Subscription subscription;

        /** Cached data item before readyToWrite. */
        @Nullable
        private T item;

        /** Cached error signal before readyToWrite. */
        @Nullable
        private Throwable error;

        /** Cached onComplete signal before readyToWrite. */
        private boolean completed;

        /** Recursive demand while emitting cached signals. */
        private long demandBeforeReadyToWrite;

        /** Current state. */
        private State state = State.NEW;

        /** The actual writeSubscriber from the HTTP server adapter. */
        @Nullable
        private Subscriber<? super T> writeSubscriber;

        WriteBarrier(CoreSubscriber<? super Void> completionSubscriber) {
            writeCompletionBarrier = new WriteCompletionBarrier(completionSubscriber, this);
        }

        // Subscriber<T> methods (we're the subscriber to the write source)..

        @Override
        public final void onSubscribe(Subscription s) {
            if (Operators.validate(subscription, s)) {
                subscription = s;
                writeCompletionBarrier.connect();
                s.request(1);
            }
        }

        @Override
        public final void onNext(T item) {
            if (state == State.READY_TO_WRITE) {
                requiredWriteSubscriber().onNext(item);
                return;
            }
            //FIXME revisit in case of reentrant sync deadlock
            synchronized (this) {
                if (state == State.READY_TO_WRITE) {
                    requiredWriteSubscriber().onNext(item);
                } else if (state == State.NEW) {
                    this.item = item;
                    state = State.FIRST_SIGNAL_RECEIVED;
                    final Publisher<Void> result;
                    try {
                        result = writeFunction.apply(this);
                    } catch (Throwable ex) {
                        writeCompletionBarrier.onError(ex);
                        return;
                    }
                    result.subscribe(writeCompletionBarrier);
                } else {
                    if (subscription != null) {
                        subscription.cancel();
                    }
                    writeCompletionBarrier.onError(new IllegalStateException("Unexpected item."));
                }
            }
        }

        private Subscriber<? super T> requiredWriteSubscriber() {
            Assert.state(writeSubscriber != null, "No write subscriber");
            return writeSubscriber;
        }

        @Override
        public final void onError(Throwable ex) {
            if (state == State.READY_TO_WRITE) {
                requiredWriteSubscriber().onError(ex);
                return;
            }
            synchronized (this) {
                if (state == State.READY_TO_WRITE) {
                    requiredWriteSubscriber().onError(ex);
                } else if (state == State.NEW) {
                    state = State.FIRST_SIGNAL_RECEIVED;
                    writeCompletionBarrier.onError(ex);
                } else {
                    error = ex;
                }
            }
        }

        @Override
        public final void onComplete() {
            if (state == State.READY_TO_WRITE) {
                requiredWriteSubscriber().onComplete();
                return;
            }
            synchronized (this) {
                if (state == State.READY_TO_WRITE) {
                    requiredWriteSubscriber().onComplete();
                } else if (state == State.NEW) {
                    completed = true;
                    state = State.FIRST_SIGNAL_RECEIVED;
                    final Publisher<Void> result;
                    try {
                        result = writeFunction.apply(this);
                    } catch (Throwable ex) {
                        writeCompletionBarrier.onError(ex);
                        return;
                    }
                    result.subscribe(writeCompletionBarrier);
                } else {
                    completed = true;
                }
            }
        }

        @Override
        public Context currentContext() {
            return writeCompletionBarrier.currentContext();
        }

        // Subscription methods (we're the Subscription to the writeSubscriber)..

        @Override
        public void request(long n) {
            final Subscription s = subscription;
            if (s == null) {
                return;
            }
            if (state == State.READY_TO_WRITE) {
                s.request(n);
                return;
            }
            synchronized (this) {
                if (writeSubscriber != null) {
                    if (state == State.EMITTING_CACHED_SIGNALS) {
                        demandBeforeReadyToWrite = n;
                        return;
                    }
                    try {
                        state = State.EMITTING_CACHED_SIGNALS;
                        if (emitCachedSignals()) {
                            return;
                        }
                        n = n + demandBeforeReadyToWrite - 1;
                        if (n == 0) {
                            return;
                        }
                    } finally {
                        state = State.READY_TO_WRITE;
                    }
                }
            }
            s.request(n);
        }

        private boolean emitCachedSignals() {
            if (error != null) {
                try {
                    requiredWriteSubscriber().onError(error);
                } finally {
                    releaseCachedItem();
                }
                return true;
            }
            final T item = this.item;
            this.item = null;
            if (item != null) {
                requiredWriteSubscriber().onNext(item);
            }
            if (completed) {
                requiredWriteSubscriber().onComplete();
                return true;
            }
            return false;
        }

        @Override
        public void cancel() {
            final Subscription s = subscription;
            if (s != null) {
                subscription = null;
                try {
                    s.cancel();
                } finally {
                    releaseCachedItem();
                }
            }
        }

        private void releaseCachedItem() {
            synchronized (this) {
                final Object item = this.item;
                if (item instanceof DataBuffer) {
                    DataBufferUtils.release((DataBuffer) item);
                }
                this.item = null;
            }
        }

        // Publisher<T> methods (we're the Publisher to the writeSubscriber)..

        @Override
        public void subscribe(Subscriber<? super T> writeSubscriber) {
            synchronized (this) {
                Assert.state(this.writeSubscriber == null, "Only one write subscriber supported");
                this.writeSubscriber = writeSubscriber;
                if (error != null || (completed && item == null)) {
                    this.writeSubscriber.onSubscribe(Operators.emptySubscription());
                    emitCachedSignals();
                } else {
                    this.writeSubscriber.onSubscribe(this);
                }
            }
        }
    }

    /**
     * We need an extra barrier between the WriteBarrier itself and the actual
     * completion subscriber.
     *
     * <p>The completionSubscriber is subscribed initially to the WriteBarrier.
     * Later after the first signal is received, we need one more subscriber
     * instance (per spec can only subscribe once) to subscribe to the write
     * function and switch to delegating completion signals from it.
     */
    private class WriteCompletionBarrier implements CoreSubscriber<Void>, Subscription {

        /* Downstream write completion subscriber */
        private final CoreSubscriber<? super Void> completionSubscriber;

        private final WriteBarrier writeBarrier;

        @Nullable
        private Subscription subscription;

        WriteCompletionBarrier(CoreSubscriber<? super Void> subscriber, WriteBarrier writeBarrier) {
            completionSubscriber = subscriber;
            this.writeBarrier = writeBarrier;
        }

        /**
         * Connect the underlying completion subscriber to this barrier in order
         * to track cancel signals and pass them on to the write barrier.
         */
        public void connect() {
            completionSubscriber.onSubscribe(this);
        }

        // Subscriber methods (we're the subscriber to the write function)..

        @Override
        public void onSubscribe(Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(Void aVoid) {
        }

        @Override
        public void onError(Throwable ex) {
            try {
                completionSubscriber.onError(ex);
            } finally {
                writeBarrier.releaseCachedItem();
            }
        }

        @Override
        public void onComplete() {
            completionSubscriber.onComplete();
        }

        @Override
        public Context currentContext() {
            return completionSubscriber.currentContext();
        }

        @Override
        public void request(long n) {
            // Ignore: we don't produce data
        }

        @Override
        public void cancel() {
            writeBarrier.cancel();
            final Subscription subscription = this.subscription;
            if (subscription != null) {
                subscription.cancel();
            }
        }
    }
}
