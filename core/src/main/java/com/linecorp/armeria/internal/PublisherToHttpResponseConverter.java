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
package com.linecorp.armeria.internal;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;

/**
 * A {@link Subscriber} which collects all objects produced by a {@link Publisher}.
 * The collected objects would be converted to an {@link HttpResponse} using the specified
 * {@link ResponseConverterFunction} when {@link #onComplete()} is called, then the {@link HttpResponse}
 * would complete the {@link CompletableFuture} which is given when creating this instance.
 */
public class PublisherToHttpResponseConverter implements Subscriber<Object> {

    private final ServiceRequestContext ctx;
    private final HttpRequest req;
    private final CompletableFuture<HttpResponse> future;
    private final ResponseConverterFunction responseConverter;
    private final ExceptionHandlerFunction exceptionHandler;

    @Nullable
    private Object firstElement;
    @Nullable
    private ImmutableList.Builder<Object> listBuilder;

    /**
     * Creates a new instance.
     *
     * @param ctx the {@link ServiceRequestContext} of the {@code req}
     * @param req the {@link HttpRequest} received by the server
     * @param future the {@link CompletableFuture} which waits for an {@link HttpResponse}
     * @param responseConverter the {@link ResponseConverterFunction} which is used to convert the
     *                          collected objects into an {@link HttpResponse} when the {@link #onComplete()}
     *                          is invoked
     * @param exceptionHandler the {@link ExceptionHandlerFunction} which is used to convert the
     *                         {@link Throwable} into an {@link HttpResponse} when the
     *                         {@link #onError(Throwable)} is invoked
     */
    public PublisherToHttpResponseConverter(ServiceRequestContext ctx, HttpRequest req,
                                            CompletableFuture<HttpResponse> future,
                                            ResponseConverterFunction responseConverter,
                                            ExceptionHandlerFunction exceptionHandler) {
        this.ctx = requireNonNull(ctx, "ctx");
        this.req = requireNonNull(req, "req");
        this.future = requireNonNull(future, "future");
        this.responseConverter = requireNonNull(responseConverter, "responseConverter");
        this.exceptionHandler = requireNonNull(exceptionHandler, "exceptionHandler");
    }

    @Override
    public void onSubscribe(Subscription s) {
        s.request(Integer.MAX_VALUE);
    }

    @Override
    public void onNext(Object o) {
        if (firstElement == null) {
            firstElement = o;
        } else {
            if (listBuilder == null) {
                listBuilder = ImmutableList.builder();
                listBuilder.add(firstElement);
            }
            listBuilder.add(o);
        }
    }

    @Override
    public void onError(Throwable t) {
        future.complete(exceptionHandler.handleException(ctx, req, t));
    }

    @Override
    public void onComplete() {
        final Object obj;
        if (listBuilder != null) {
            obj = listBuilder.build();
            listBuilder = null;
        } else {
            obj = firstElement;
        }
        firstElement = null;

        try {
            future.complete(responseConverter.convertResponse(ctx, obj));
        } catch (Exception e) {
            onError(e);
        }
    }
}
