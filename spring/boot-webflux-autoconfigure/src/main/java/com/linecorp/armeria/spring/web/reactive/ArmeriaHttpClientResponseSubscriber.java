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
import java.util.function.BiFunction;

import javax.annotation.Nullable;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.common.stream.AbortedStreamException;
import com.linecorp.armeria.common.stream.CancelledSubscriptionException;

import io.netty.channel.EventLoop;

/**
 * A {@link Subscriber} which reads the {@link HttpHeaders} first from an {@link HttpResponse}.
 * If the {@link HttpHeaders} is consumed, it completes the {@code future} with the {@link HttpHeaders}.
 * After that, it can act as a {@link Publisher} on behalf of the {@link HttpResponse},
 * by calling {@link #toResponseBodyPublisher()} which returns {@link ResponseBodyPublisher}.
 */
final class ArmeriaHttpClientResponseSubscriber
        implements Subscriber<HttpObject>, BiFunction<Void, Throwable, Void> {

    private final CompletableFuture<HttpHeaders> future = new CompletableFuture<>();
    private final EventLoop eventLoop = CommonPools.workerGroup().next();

    @Nullable
    private ResponseBodyPublisher publisher;

    @Nullable
    private Subscription subscription;

    private boolean isCompleted;
    @Nullable
    private Throwable completedCause;

    ArmeriaHttpClientResponseSubscriber(HttpResponse httpResponse) {
        httpResponse.completionFuture().handle(this);
        httpResponse.subscribe(this, eventLoop);
    }

    CompletableFuture<HttpHeaders> httpHeadersFuture() {
        return future;
    }

    @Override
    public void onSubscribe(Subscription s) {
        subscription = s;
        s.request(1);
    }

    @Override
    public void onNext(HttpObject httpObject) {
        if (publisher != null) {
            publisher.relayOnNext(httpObject);
            return;
        }
        if (httpObject instanceof HttpHeaders) {
            final HttpHeaders headers = (HttpHeaders) httpObject;
            final HttpStatus status = headers.status();
            if (status != null && status.codeClass() != HttpStatusClass.INFORMATIONAL) {
                future.complete(headers);
                return;
            }
        }
        if (!future.isDone()) {
            ensureSubscribed().request(1);
        }
    }

    @Override
    public void onError(Throwable cause) {
        if (publisher != null) {
            publisher.relayOnError(cause);
        } else {
            complete(cause);
        }
    }

    @Override
    public void onComplete() {
        if (publisher != null) {
            publisher.relayOnComplete();
        } else {
            complete(null);
        }
    }

    private void complete(@Nullable Throwable cause) {
        isCompleted = true;
        completedCause = cause;
    }

    @Override
    public Void apply(Void unused, Throwable cause) {
        if (future.isDone()) {
            return null;
        }

        if (cause != null && !(cause instanceof CancelledSubscriptionException) &&
            !(cause instanceof AbortedStreamException)) {
            future.completeExceptionally(cause);
        } else {
            future.complete(HttpHeaders.EMPTY_HEADERS);
        }
        return null;
    }

    private Subscription ensureSubscribed() {
        return ensureSubscribed(subscription);
    }

    private static <T> T ensureSubscribed(@Nullable T s) {
        if (s == null) {
            throw new IllegalStateException("No subscriber has been subscribed.");
        }
        return s;
    }

    ResponseBodyPublisher toResponseBodyPublisher() {
        if (!future.isDone()) {
            throw new IllegalStateException("HTTP headers have not been consumed yet.");
        }
        final ResponseBodyPublisher publisher = new ResponseBodyPublisher(this);
        this.publisher = publisher;
        return publisher;
    }

    final class ResponseBodyPublisher implements Publisher<HttpObject>, Subscription {

        @Nullable
        private Subscriber<? super HttpObject> subscriber;

        private final ArmeriaHttpClientResponseSubscriber upstreamSubscriber;

        private ResponseBodyPublisher(ArmeriaHttpClientResponseSubscriber upstreamSubscriber) {
            this.upstreamSubscriber = upstreamSubscriber;
        }

        @Override
        public void subscribe(Subscriber<? super HttpObject> s) {
            subscriber = s;
            s.onSubscribe(this);
        }

        @Override
        public void request(long n) {
            if (!isCompleted) {
                upstreamSubscriber.ensureSubscribed().request(n);
                return;
            }

            // If this stream is already completed, invoke 'onComplete' or 'onError' in asynchronous manner.
            eventLoop.execute(() -> {
                if (completedCause != null) {
                    relayOnError(completedCause);
                } else {
                    relayOnComplete();
                }
            });
        }

        @Override
        public void cancel() {
            upstreamSubscriber.ensureSubscribed().cancel();
        }

        private void relayOnNext(HttpObject httpObject) {
            ensureSubscribed(subscriber).onNext(httpObject);
        }

        private void relayOnError(Throwable cause) {
            ensureSubscribed(subscriber).onError(cause);
        }

        private void relayOnComplete() {
            ensureSubscribed(subscriber).onComplete();
        }
    }
}
