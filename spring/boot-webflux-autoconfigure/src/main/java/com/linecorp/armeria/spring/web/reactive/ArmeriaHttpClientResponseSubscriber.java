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

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.stream.AbortedStreamException;
import com.linecorp.armeria.common.stream.CancelledSubscriptionException;

import io.netty.channel.EventLoop;
import reactor.core.publisher.EmitterProcessor;
import reactor.core.scheduler.Schedulers;

/**
 * A {@link Subscriber} which reads the {@link ResponseHeaders} first from an {@link HttpResponse}.
 * If the {@link ResponseHeaders} is consumed, it completes the {@code future} with the {@link ResponseHeaders}.
 * After that, it can act as a {@link Publisher} on behalf of the {@link HttpResponse},
 * by calling {@link #toResponseBodyPublisher()} which returns {@link ResponseBodyPublisher}.
 */
final class ArmeriaHttpClientResponseSubscriber
        implements BiFunction<Void, Throwable, Void> {

    private final CompletableFuture<ResponseHeaders> future = new CompletableFuture<>();
    private final EventLoop eventLoop = CommonPools.workerGroup().next();
    private final EmitterProcessor<HttpData> httpDataProcessor = EmitterProcessor.create(1);

    ArmeriaHttpClientResponseSubscriber(HttpResponse httpResponse) {
        final EmitterProcessor<HttpObject> httpObjectProcessor = EmitterProcessor.create(1);
        httpObjectProcessor
                .ofType(HttpData.class)
                .subscribe(httpDataProcessor);

        httpObjectProcessor
                .ofType(ResponseHeaders.class)
                .filter(headers -> headers.status().codeClass() != HttpStatusClass.INFORMATIONAL)
                .limitRequest(1)
                .subscribe(future::complete);

        httpResponse.completionFuture().handle(this);
        httpResponse.subscribe(httpObjectProcessor, eventLoop);
    }

    CompletableFuture<ResponseHeaders> httpHeadersFuture() {
        return future;
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
            future.complete(ResponseHeaders.of(HttpStatus.UNKNOWN));
        }
        return null;
    }

    ResponseBodyPublisher toResponseBodyPublisher() {
        if (!future.isDone()) {
            throw new IllegalStateException("HTTP headers have not been consumed yet.");
        }
        return new ResponseBodyPublisher();
    }

    final class ResponseBodyPublisher implements Publisher<HttpObject> {
        @Override
        public void subscribe(Subscriber<? super HttpObject> s) {
            httpDataProcessor
                    .publishOn(Schedulers.fromExecutorService(eventLoop), 1)
                    .subscribe(s);
        }
    }
}
