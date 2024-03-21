/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.internal.server.annotation;

import static com.linecorp.armeria.internal.common.util.ObjectCollectingUtil.collectFrom;
import static com.linecorp.armeria.internal.server.annotation.ClassUtil.typeToClass;
import static com.linecorp.armeria.internal.server.annotation.ClassUtil.unwrapUnaryAsyncType;

import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import org.reactivestreams.Publisher;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;

/**
 * A response converter implementation which creates an {@link HttpResponse} with
 * the objects published from a {@link Publisher} or {@link Stream}.
 */
final class AggregatedResponseConverterFunction implements ResponseConverterFunction {

    private final ResponseConverterFunction responseConverter;

    AggregatedResponseConverterFunction(ResponseConverterFunction responseConverter) {
        this.responseConverter = responseConverter;
    }

    @Override
    public Boolean isResponseStreaming(Type returnType, @Nullable MediaType contentType) {
        final Class<?> clazz = typeToClass(unwrapUnaryAsyncType(returnType));
        if (clazz == null) {
            return null;
        }

        if (HttpResponse.class.isAssignableFrom(clazz)) {
            return true;
        }
        if (Publisher.class.isAssignableFrom(clazz) || Stream.class.isAssignableFrom(clazz)) {
            return false;
        }

        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public HttpResponse convertResponse(ServiceRequestContext ctx, ResponseHeaders headers,
                                        @Nullable Object result, HttpHeaders trailers) throws Exception {
        final CompletableFuture<?> f;
        if (result instanceof Publisher) {
            f = collectFrom((Publisher<Object>) result, ctx);
        } else if (result instanceof Stream) {
            f = collectFrom((Stream<Object>) result, ctx.blockingTaskExecutor());
        } else {
            return ResponseConverterFunction.fallthrough();
        }

        return HttpResponse.of(f.thenApply(aggregated -> {
            try {
                return responseConverter.convertResponse(ctx, headers, aggregated, trailers);
            } catch (Exception ex) {
                return Exceptions.throwUnsafely(ex);
            }
        }));
    }
}
