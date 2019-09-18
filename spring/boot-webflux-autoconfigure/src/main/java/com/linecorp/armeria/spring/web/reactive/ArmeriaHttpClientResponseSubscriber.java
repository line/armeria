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
package com.linecorp.armeria.spring.web.reactive;

import static com.google.common.base.Preconditions.checkState;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import javax.annotation.Nullable;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.stream.AbortedStreamException;
import com.linecorp.armeria.common.stream.CancelledSubscriptionException;
import com.linecorp.armeria.common.stream.SubscriptionOption;

import io.netty.channel.EventLoop;

/**
 * A {@link Subscriber} which reads the {@link ResponseHeaders} first from an {@link HttpResponse}.
 * If the {@link ResponseHeaders} is consumed, it completes the {@code future} with the {@link ResponseHeaders}.
 * After that, it can act as a {@link Publisher} on behalf of the {@link HttpResponse},
 * by calling {@link #toResponseBodyPublisher()} which returns {@link ResponseBodyPublisher}.
 */
final class ArmeriaHttpClientResponseSubscriber implements Subscriber<HttpObject> {

    private final CompletableFuture<ResponseHeaders> headersFuture = new CompletableFuture<>();
    private final EventLoop eventLoop = CommonPools.workerGroup().next();

    private final ResponseBodyPublisher bodyPublisher = new ResponseBodyPublisher(this);

    @Nullable
    private volatile Subscription subscription;

    private boolean isCompleted;

    @Nullable
    private Throwable completedCause;

    ArmeriaHttpClientResponseSubscriber(HttpResponse httpResponse) {
        httpResponse.subscribe(this, eventLoop, SubscriptionOption.NOTIFY_CANCELLATION);
    }

    CompletableFuture<ResponseHeaders> headersFuture() {
        return headersFuture;
    }

    @Override
    public void onSubscribe(Subscription s) {
        subscription = s;
        s.request(1);
    }

    @Override
    public void onNext(HttpObject httpObject) {
        if (httpObject instanceof ResponseHeaders) {
            final ResponseHeaders headers = (ResponseHeaders) httpObject;
            final HttpStatus status = headers.status();
            if (status.codeClass() != HttpStatusClass.INFORMATIONAL) {
                headersFuture.complete(headers);
                return;
            }
        }

        if (!headersFuture.isDone()) {
            upstreamSubscription().request(1);
            return;
        }

        bodyPublisher.relayOnNext(httpObject);
    }

    @Override
    public void onComplete() {
        complete(null);
    }

    @Override
    public void onError(Throwable cause) {
        complete(cause);
    }

    private void complete(@Nullable Throwable cause) {
        if (isCompleted) {
            return;
        }

        isCompleted = true;
        completedCause = cause;

        // Complete the future for the response headers if it did not receive any non-informational headers yet.
        if (!headersFuture.isDone()) {
            if (cause != null && !(cause instanceof CancelledSubscriptionException) &&
                !(cause instanceof AbortedStreamException)) {
                headersFuture.completeExceptionally(cause);
            } else {
                headersFuture.complete(ResponseHeaders.of(HttpStatus.UNKNOWN));
            }
        }

        // Notify the publisher.
        bodyPublisher.relayOnComplete(cause);
    }

    Subscription upstreamSubscription() {
        final Subscription subscription = this.subscription;
        if (subscription == null) {
            throw new IllegalStateException("No subscriber has been subscribed.");
        }
        return subscription;
    }

    ResponseBodyPublisher toResponseBodyPublisher() {
        if (!headersFuture.isDone()) {
            throw new IllegalStateException("HTTP headers have not been consumed yet.");
        }
        return bodyPublisher;
    }

    static final class ResponseBodyPublisher implements Publisher<HttpObject>, Subscription {

        @SuppressWarnings("rawtypes")
        private static final AtomicReferenceFieldUpdater<ResponseBodyPublisher, Subscriber> subscriberUpdater =
                AtomicReferenceFieldUpdater.newUpdater(ResponseBodyPublisher.class, Subscriber.class,
                                                       "subscriber");

        private final ArmeriaHttpClientResponseSubscriber parent;

        @Nullable
        private volatile Subscriber<? super HttpObject> subscriber;

        ResponseBodyPublisher(ArmeriaHttpClientResponseSubscriber parent) {
            this.parent = parent;
        }

        @Override
        public void subscribe(Subscriber<? super HttpObject> s) {
            for (;;) {
                final Subscriber<? super HttpObject> oldSubscriber = subscriber;
                if (oldSubscriber == null) {
                    // The first subscriber.
                    if (subscriberUpdater.compareAndSet(this, null, s)) {
                        break;
                    }
                } else {
                    // The second subscriber.
                    final Subscriber<HttpObject> newSubscriber = new CompositeSubscriber<>(oldSubscriber, s);
                    if (subscriberUpdater.compareAndSet(this, oldSubscriber, newSubscriber)) {
                        break;
                    }
                }
            }

            s.onSubscribe(this);
        }

        @Override
        public void request(long n) {
            if (!parent.isCompleted) {
                parent.upstreamSubscription().request(n);
                return;
            }

            // If this stream is already completed, invoke 'onComplete' or 'onError' asynchronously.
            parent.eventLoop.execute(() -> relayOnComplete(parent.completedCause));
        }

        @Override
        public void cancel() {
            parent.upstreamSubscription().cancel();
        }

        private void relayOnNext(HttpObject obj) {
            final Subscriber<? super HttpObject> subscriber = this.subscriber;
            checkState(subscriber != null,
                       "HttpObject was relayed downstream when there's no subscriber: %s", obj);
            subscriber.onNext(obj);
        }

        private void relayOnComplete(@Nullable Throwable cause) {
            final Subscriber<? super HttpObject> subscriber = this.subscriber;
            if (subscriber != null) {
                if (cause == null) {
                    subscriber.onComplete();
                } else {
                    subscriber.onError(cause);
                }
            } else {
                // If there's no Subscriber yet, we can notify later
                // when a Subscriber subscribes and calls Subscription.request().
            }
        }
    }

    private static final class CompositeSubscriber<T> implements Subscriber<T> {

        private final Subscriber<? super T> mainSubscriber;
        private final Subscriber<? super T> subSubscriber;

        CompositeSubscriber(Subscriber<? super T> mainSubscriber, Subscriber<? super T> subSubscriber) {
            this.mainSubscriber = mainSubscriber;
            this.subSubscriber = subSubscriber;
        }

        /**
         * This method is never invoked because {@link ResponseBodyPublisher#subscribe(Subscriber)} invokes
         * the {@link Subscriber}s.
         */
        @Override
        public void onSubscribe(Subscription s) {
            throw new Error();
        }

        @Override
        public void onNext(T t) {
            // Note that only the main Subscriber gets the data.
            // Other Subscribers only get whether the response streaming was successful or not.
            mainSubscriber.onNext(t);
        }

        @Override
        public void onError(Throwable t) {
            try {
                mainSubscriber.onError(t);
            } finally {
                subSubscriber.onError(t);
            }
        }

        @Override
        public void onComplete() {
            try {
                mainSubscriber.onComplete();
            } finally {
                subSubscriber.onComplete();
            }
        }
    }
}
