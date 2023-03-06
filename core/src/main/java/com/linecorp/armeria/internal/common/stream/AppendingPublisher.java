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

package com.linecorp.armeria.internal.common.stream;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.atomic.AtomicLongFieldUpdater;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.math.LongMath;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.NoopSubscriber;

public final class AppendingPublisher<T> implements Publisher<T> {

    private final Publisher<? extends T> publisher;
    private final T append;

    public AppendingPublisher(Publisher<? extends T> publisher, T append) {
        this.publisher = publisher;
        this.append = append;
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber) {
        requireNonNull(subscriber, "subscriber");
        subscriber.onSubscribe(new AppendSubscriber<>(publisher, append, subscriber));
    }

    static final class AppendSubscriber<T> implements Subscriber<T>, Subscription {

        @SuppressWarnings("rawtypes")
        private static final AtomicLongFieldUpdater<AppendSubscriber> requestedUpdater =
                AtomicLongFieldUpdater.newUpdater(AppendSubscriber.class, "requested");

        @SuppressWarnings("rawtypes")
        private static final AtomicLongFieldUpdater<AppendSubscriber> needToPublishUpdater =
                AtomicLongFieldUpdater.newUpdater(AppendSubscriber.class, "needToPublish");

        private final Publisher<? extends T> publisher;
        private final T append;
        private Subscriber<? super T> downstream;
        @Nullable
        private volatile Subscription upstream;

        private volatile long requested;
        private volatile long needToPublish;
        private boolean subscribed;
        private volatile boolean cancelled;

        private boolean appendNeedToSend;
        private boolean appendSent;
        private boolean completeSent;

        AppendSubscriber(Publisher<? extends T> publisher, T append, Subscriber<? super T> downstream) {
            requireNonNull(publisher, "publisher");
            requireNonNull(append, "append");
            requireNonNull(downstream, "downstream");
            this.publisher = publisher;
            this.append = append;
            this.downstream = downstream;
        }

        @Override
        public void request(long n) {
            if (n <= 0) {
                downstream.onError(new IllegalArgumentException("non-positive request signals are illegal"));
                return;
            }
            if (cancelled || completeSent) {
                return;
            }
            if (appendSent) {
                complete();
                return;
            }
            if (appendNeedToSend) {
                append();
                if (n > 1) {
                    complete();
                }
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
            if (!subscribed) {
                subscribed = true;
                publisher.subscribe(this);
            }
            requestUpstream();
        }

        private void append() {
            appendSent = true;
            downstream.onNext(append);
        }

        private void complete() {
            completeSent = true;
            downstream.onComplete();
        }

        private void requestUpstream() {
            for (;;) {
                final Subscription upstream = this.upstream;
                final long requested = this.requested;
                if (upstream == null || requested == 0) {
                    return;
                }
                if (requestedUpdater.compareAndSet(this, requested, 0)) {
                    needToPublishUpdater.addAndGet(this, requested);
                    upstream.request(requested);
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
            requestUpstream();
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
            if (needToPublish == 0) {
                appendNeedToSend = true;
                return;
            }

            append();
            if (needToPublish > 1) {
                complete();
            }
        }
    }
}
