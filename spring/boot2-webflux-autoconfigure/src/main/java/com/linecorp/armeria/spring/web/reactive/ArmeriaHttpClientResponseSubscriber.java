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
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.stream.AbortedStreamException;
import com.linecorp.armeria.common.stream.CancelledSubscriptionException;
import com.linecorp.armeria.common.stream.SubscriptionOption;

import io.netty.channel.EventLoop;
import reactor.core.publisher.Mono;

/**
 * A {@link Subscriber} which reads the {@link ResponseHeaders} first from an {@link HttpResponse}.
 * If the {@link ResponseHeaders} is consumed, it completes the {@link #headersFuture} with
 * the {@link ResponseHeaders}. After that, it can act as a {@link Publisher} on behalf of
 * the {@link HttpResponse}, by calling {@link #toResponseBodyPublisher()}
 * which returns {@link ResponseBodyPublisher}.
 */
final class ArmeriaHttpClientResponseSubscriber implements Subscriber<HttpObject> {

    private static final Throwable SUCCESS = new Throwable("SUCCESS", null, false, false) {
        private static final long serialVersionUID = 16755233460671894L;
    };

    private final CompletableFuture<ResponseHeaders> headersFuture = new CompletableFuture<>();
    private final CompletableFuture<Void> completionFuture;
    private final EventLoop eventLoop = CommonPools.workerGroup().next();

    private final ResponseBodyPublisher bodyPublisher = new ResponseBodyPublisher(this);

    @Nullable
    private volatile Subscription subscription;

    @Nullable
    private volatile Throwable completedCause;

    ArmeriaHttpClientResponseSubscriber(HttpResponse httpResponse) {
        completionFuture = httpResponse.whenComplete();
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
            if (!status.isInformational()) {
                headersFuture.complete(headers);
                return;
            }
        }

        if (!headersFuture.isDone()) {
            final Subscription subscription = this.subscription;
            assert subscription != null;
            subscription.request(1);
            return;
        }

        final Subscriber<? super HttpObject> subscriber = bodyPublisher.subscriber;
        if (subscriber == null) {
            onError(new IllegalStateException(
                    "HttpObject was relayed downstream when there's no subscriber: " + httpObject));
            final Subscription subscription = this.subscription;
            assert subscription != null;
            subscription.cancel();
            return;
        }
        subscriber.onNext(httpObject);
    }

    @Override
    public void onComplete() {
        complete(SUCCESS);
    }

    @Override
    public void onError(Throwable cause) {
        complete(cause);
    }

    private void complete(Throwable cause) {
        completedCause = cause;

        // Complete the future for the response headers if it did not receive any non-informational headers yet.
        if (!headersFuture.isDone()) {
            if (cause != SUCCESS && !(cause instanceof CancelledSubscriptionException) &&
                !(cause instanceof AbortedStreamException)) {
                headersFuture.completeExceptionally(cause);
            } else {
                headersFuture.complete(ResponseHeaders.of(HttpStatus.UNKNOWN));
            }
        }

        // Notify the publisher.
        bodyPublisher.relayOnComplete();
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

        @Nullable
        private Publisher<HttpObject> publisherForLateSubscribers;

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
                        s.onSubscribe(this);
                        break;
                    }
                } else {
                    // The other subscribers - notify whether completed successfully only.
                    if (publisherForLateSubscribers == null) {
                        @SuppressWarnings({ "unchecked", "rawtypes" })
                        final Publisher<HttpObject> newPublisher =
                                (Publisher) Mono.fromFuture(parent.completionFuture);
                        publisherForLateSubscribers = newPublisher;
                    }
                    publisherForLateSubscribers.subscribe(s);
                    break;
                }
            }
        }

        @Override
        public void request(long n) {
            if (parent.completedCause == null) {
                // The stream is not completed yet.
                parent.upstreamSubscription().request(n);
            } else {
                // If this stream is already completed, invoke 'onComplete' or 'onError' asynchronously.
                parent.eventLoop.execute(this::relayOnComplete);
            }
        }

        @Override
        public void cancel() {
            parent.upstreamSubscription().cancel();
        }

        private void relayOnComplete() {
            final Subscriber<? super HttpObject> subscriber = this.subscriber;
            final Throwable cause = parent.completedCause;
            assert cause != null;

            if (subscriber != null) {
                if (cause == SUCCESS) {
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
}
