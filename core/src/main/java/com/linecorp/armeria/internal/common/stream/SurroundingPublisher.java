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

package com.linecorp.armeria.internal.common.stream;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.math.LongMath;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.NoopSubscriber;

public final class SurroundingPublisher<T> implements Publisher<T> {

    @Nullable
    private final T head;
    private final Publisher<? extends T> publisher;
    @Nullable
    private final T tail;

    public SurroundingPublisher(@Nullable T head, Publisher<? extends T> publisher, @Nullable T tail) {
        requireNonNull(publisher, "publisher");
        this.head = head;
        this.publisher = publisher;
        this.tail = tail;
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber) {
        subscriber.onSubscribe(new SurroundingSubscriber<>(head, publisher, tail, subscriber));
    }

    static final class SurroundingSubscriber<T> implements Subscriber<T>, Subscription {

        @SuppressWarnings("rawtypes")
        private static final AtomicReferenceFieldUpdater<SurroundingSubscriber, State> stateUpdater =
                AtomicReferenceFieldUpdater.newUpdater(SurroundingSubscriber.class, State.class, "state");

        @SuppressWarnings("rawtypes")
        private static final AtomicLongFieldUpdater<SurroundingSubscriber> requestedUpdater =
                AtomicLongFieldUpdater.newUpdater(SurroundingSubscriber.class, "requested");

        @SuppressWarnings("rawtypes")
        private static final AtomicLongFieldUpdater<SurroundingSubscriber> needToPublishUpdater =
                AtomicLongFieldUpdater.newUpdater(SurroundingSubscriber.class, "needToPublish");

        enum State {
            SENDING_HEAD,
            SENDING_BODY,
            SENDING_TAIL,
            SENDING_COMPLETE,
            DONE,
        }

        private volatile State state = State.SENDING_HEAD;

        @Nullable
        private final T head;
        private final Publisher<? extends T> publisher;
        @Nullable
        private final T tail;
        private Subscriber<? super T> downstream;
        @Nullable
        private volatile Subscription upstream;

        private volatile long requested;
        private volatile long needToPublish;
        private volatile boolean cancelled;

        SurroundingSubscriber(@Nullable T head, Publisher<? extends T> publisher, @Nullable T tail,
                              Subscriber<? super T> downstream) {
            requireNonNull(publisher, "publisher");
            requireNonNull(downstream, "downstream");
            this.head = head;
            this.publisher = publisher;
            this.tail = tail;
            this.downstream = downstream;
        }

        @Override
        public void request(long n) {
            if (n <= 0) {
                downstream.onError(new IllegalArgumentException("non-positive request signals are illegal"));
                return;
            }
            if (cancelled || state == State.DONE) {
                return;
            }
            for (;;) {
                final long oldRequested = requested;
                final long newRequested = LongMath.saturatedAdd(oldRequested, n);
                if (requestedUpdater.compareAndSet(this, oldRequested, newRequested)) {
                    if (oldRequested > 0) {
                        return;
                    }
                    break;
                }
            }

            switch (state) {
                case SENDING_HEAD: {
                    setState(State.SENDING_HEAD, State.SENDING_BODY);
                    if (head != null) {
                        downstream.onNext(head);
                        requestedUpdater.decrementAndGet(this);
                    }
                    publisher.subscribe(this);
                    return;
                }
                case SENDING_BODY: {
                    requestUpstream(upstream);
                    return;
                }
                case SENDING_TAIL: {
                    sendTail();
                    if (n > 1) {
                        sendComplete();
                    }
                    return;
                }
                case SENDING_COMPLETE: {
                    sendComplete();
                    return;
                }
                case DONE: {
                    upstream.cancel();
                    return;
                }
            }
        }

        private void sendTail() {
            setState(State.SENDING_TAIL, State.SENDING_COMPLETE);
            if (tail != null) {
                requestedUpdater.decrementAndGet(this);
                downstream.onNext(tail);
            } else {
                sendComplete();
            }
        }

        private void sendComplete() {
            if (state == State.DONE) {
                return;
            }
            setState(State.SENDING_COMPLETE, State.DONE);
            downstream.onComplete();
        }

        private void requestUpstream(Subscription subscription) {
            for (;;) {
                final long requested = this.requested;
                if (requested == 0) {
                    return;
                }
                if (state == State.SENDING_TAIL) {
                    sendTail();
                    if (requested > 1) {
                        sendComplete();
                    }
                    return;
                }
                if (requestedUpdater.compareAndSet(this, requested, 0)) {
                    if (needToPublishUpdater.get(this) > Long.MAX_VALUE - requested) {
                        needToPublishUpdater.set(this, Long.MAX_VALUE);
                    } else {
                        needToPublishUpdater.addAndGet(this, requested);
                    }
                    subscription.request(requested);
                    return;
                }
            }
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            requireNonNull(subscription, "subscription");
            if (cancelled) {
                subscription.cancel();
                return;
            }
            upstream = subscription;
            requestUpstream(subscription);
        }

        @Override
        public void onNext(T item) {
            requireNonNull(item, "item");
            needToPublishUpdater.decrementAndGet(this);
            downstream.onNext(item);
        }

        @Override
        public void onError(Throwable cause) {
            requireNonNull(cause, "cause");
            if (cancelled) {
                return;
            }
            cancelled = true;
            downstream.onError(cause);
        }

        @Override
        public void onComplete() {
            final long needToPublish = needToPublishUpdater.get(this);
            setState(State.SENDING_BODY, State.SENDING_TAIL);
            if (needToPublish == 0) {
                return;
            }

            sendTail();
            if (needToPublish > 1) {
                sendComplete();
            }
        }

        @Override
        public void cancel() {
            if (cancelled) {
                return;
            }
            cancelled = true;
            downstream = NoopSubscriber.get();
            final Subscription upstream = this.upstream;
            if (upstream != null) {
                upstream.cancel();
            }
        }

        private boolean setState(State oldState, State newState) {
            assert newState != State.SENDING_HEAD : "oldState: " + oldState + ", newState: " + newState;
            return stateUpdater.compareAndSet(this, oldState, newState);
        }
    }
}
