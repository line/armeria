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
package com.linecorp.armeria.server.rxjava3;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;

import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.Single;

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
            @SuppressWarnings("unchecked")
            final CompletionStage<Object> future = ((Maybe<Object>) result).toCompletionStage(null);
            return HttpResponse.from(future.handle(handleResult(ctx, headers, trailers)));
        }

        if (result instanceof Single) {
            @SuppressWarnings("unchecked")
            final CompletionStage<Object> future = ((Single<Object>) result).toCompletionStage();
            return HttpResponse.from(future.handle(handleResult(ctx, headers, trailers)));
        }

        if (result instanceof Completable) {
            final CompletionStage<Object> future = ((Completable) result).toCompletionStage(null);
            return HttpResponse.from(future.handle(handleResult(ctx, headers, trailers)));
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

    private BiFunction<Object, Throwable, HttpResponse> handleResult(
            ServiceRequestContext ctx, ResponseHeaders headers, HttpHeaders trailers) {
        return (result, cause) -> {
            if (cause != null) {
                return onError(ctx, cause);
            }
            return onSuccess(ctx, headers, result, trailers);
        };
    }
}
