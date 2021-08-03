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
package com.linecorp.armeria.server.rxjava2;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;

import io.reactivex.BackpressureStrategy;
import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Maybe;
import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.disposables.Disposable;

/**
 * A {@link ResponseConverterFunction} which converts the {@link Observable} instance to a {@link Flowable}
 * first, then converts it to an {@link HttpResponse} using the specified {@code responseConverter}.
 * The types, which publish 0 or 1 object such as {@link Single}, {@link Maybe} and {@link Completable},
 * would not be converted into a {@link Flowable}.
 */
public final class ObservableResponseConverterFunction implements ResponseConverterFunction {

    private final ResponseConverterFunction responseConverter;
    @Nullable
    private final ExceptionHandlerFunction exceptionHandler;

    /**
     * Creates a new {@link ResponseConverterFunction} instance.
     *
     * @param responseConverter the function which converts an object with the configured
     *                          {@link ResponseConverterFunction}
     * @param exceptionHandler the function which converts a {@link Throwable} with the configured
     *                         {@link ExceptionHandlerFunction}
     * @deprecated The registered {@link ExceptionHandlerFunction}s will be applied automatically.
     *             Use {@link #ObservableResponseConverterFunction(ResponseConverterFunction)} instead.
     */
    @Deprecated
    public ObservableResponseConverterFunction(ResponseConverterFunction responseConverter,
                                               ExceptionHandlerFunction exceptionHandler) {
        this.responseConverter = requireNonNull(responseConverter, "responseConverter");
        this.exceptionHandler = requireNonNull(exceptionHandler, "exceptionHandler");
    }

    /**
     * Creates a new {@link ResponseConverterFunction} instance.
     *
     * @param responseConverter the function which converts an object with the configured
     *                          {@link ResponseConverterFunction}
     */
    public ObservableResponseConverterFunction(ResponseConverterFunction responseConverter) {
        this.responseConverter = requireNonNull(responseConverter, "responseConverter");
        exceptionHandler = null;
    }

    @Override
    public HttpResponse convertResponse(ServiceRequestContext ctx,
                                        ResponseHeaders headers,
                                        @Nullable Object result,
                                        HttpHeaders trailers) throws Exception {
        if (result instanceof Observable) {
            return responseConverter.convertResponse(
                    ctx, headers, ((Observable<?>) result).toFlowable(BackpressureStrategy.BUFFER), trailers);
        }

        if (result instanceof Maybe) {
            final CompletableFuture<HttpResponse> future = new CompletableFuture<>();
            final Disposable disposable = ((Maybe<?>) result).subscribe(
                    o -> future.complete(onSuccess(ctx, headers, o, trailers)),
                    cause -> future.complete(onError(ctx, cause)),
                    () -> future.complete(onSuccess(ctx, headers, null, trailers)));
            return respond(future, disposable);
        }

        if (result instanceof Single) {
            final CompletableFuture<HttpResponse> future = new CompletableFuture<>();
            final Disposable disposable = ((Single<?>) result).subscribe(
                    o -> future.complete(onSuccess(ctx, headers, o, trailers)),
                    cause -> future.complete(onError(ctx, cause)));
            return respond(future, disposable);
        }

        if (result instanceof Completable) {
            final CompletableFuture<HttpResponse> future = new CompletableFuture<>();
            final Disposable disposable = ((Completable) result).subscribe(
                    () -> future.complete(onSuccess(ctx, headers, null, trailers)),
                    cause -> future.complete(onError(ctx, cause)));
            return respond(future, disposable);
        }

        return ResponseConverterFunction.fallthrough();
    }

    private HttpResponse onSuccess(ServiceRequestContext ctx,
                                   ResponseHeaders headers,
                                   @Nullable Object result,
                                   HttpHeaders trailers) {
        try {
            return responseConverter.convertResponse(ctx, headers, result, trailers);
        } catch (Exception e) {
            return onError(ctx, e);
        }
    }

    private HttpResponse onError(ServiceRequestContext ctx, Throwable cause) {
        if (exceptionHandler == null) {
            return HttpResponse.ofFailure(cause);
        } else {
            // TODO(ikhoon): Remove this line once the deprecated exceptionHandler has been removed.
            return exceptionHandler.handleException(ctx, ctx.request(), cause);
        }
    }

    private static HttpResponse respond(CompletableFuture<HttpResponse> future, Disposable disposable) {
        final HttpResponse response = HttpResponse.from(future);
        response.whenComplete().exceptionally(cause -> {
            disposable.dispose();
            return null;
        });
        return response;
    }
}
