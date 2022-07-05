/*
Copyright 2022 LINE Corporation

LINE Corporation licenses this file to you under the Apache License,
version 2.0 (the "License"); you may not use this file except in compliance
with the License. You may obtain a copy of the License at:

  https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
License for the specific language governing permissions and limitations
under the License.
 */

package com.linecorp.armeria.internal.server.annotation;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.ByteArrayResponseConverterFunction;
import com.linecorp.armeria.server.annotation.HttpFileResponseConverterFunction;
import com.linecorp.armeria.server.annotation.HttpResult;
import com.linecorp.armeria.server.annotation.JacksonResponseConverterFunction;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;
import com.linecorp.armeria.server.annotation.DelegatingResponseConverterFunctionProvider;
import com.linecorp.armeria.server.annotation.StringResponseConverterFunction;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.linecorp.armeria.internal.common.util.ObjectCollectingUtil.collectFrom;

final class ResponseConverterFunctionSelector {

    private ResponseConverterFunctionSelector() {}

    private static final Logger logger =
            LoggerFactory.getLogger(ResponseConverterFunctionSelector.class);

    static final List<DelegatingResponseConverterFunctionProvider> responseConverterFunctionProviders =
            ImmutableList.copyOf(ServiceLoader.load(DelegatingResponseConverterFunctionProvider.class,
                                                    AnnotatedService.class.getClassLoader()));

    static {
        if (!responseConverterFunctionProviders.isEmpty()) {
            logger.debug("Available {}s: {}", DelegatingResponseConverterFunctionProvider.class.getSimpleName(),
                         responseConverterFunctionProviders);
        }
    }

    static ResponseConverterFunction responseConverter(
            Method method, List<ResponseConverterFunction> responseConverters) {

        final Type actualType = getActualReturnType(method);
        final ImmutableList<ResponseConverterFunction> backingConverters =
                ImmutableList
                        .<ResponseConverterFunction>builder()
                        .addAll(responseConverters)
                        .addAll(defaultResponseConverters)
                        .build();
        final ResponseConverterFunction responseConverter = new CompositeResponseConverterFunction(
                ImmutableList
                        .<ResponseConverterFunction>builder()
                        .addAll(backingConverters)
                        // It is the last converter to try to convert the result object into an HttpResponse
                        // after aggregating the published object from a Publisher or Stream.
                        .add(new AggregatedResponseConverterFunction(
                                new CompositeResponseConverterFunction(backingConverters)))
                        .build());

        for (final DelegatingResponseConverterFunctionProvider provider : responseConverterFunctionProviders) {
            final ResponseConverterFunction func =
                    provider.createResponseConverterFunction(actualType, responseConverter);
            if (func != null) {
                return func;
            }
        }

        return responseConverter;
    }

    private static Type getActualReturnType(Method method) {
        final Class<?> returnType;
        final Type genericReturnType;

        if (KotlinUtil.isKFunction(method)) {
            returnType = KotlinUtil.kFunctionReturnType(method);
            if (KotlinUtil.isReturnTypeNothing(method)) {
                genericReturnType = KotlinUtil.kFunctionReturnType(method);
            } else {
                genericReturnType = KotlinUtil.kFunctionGenericReturnType(method);
            }
        } else {
            returnType = method.getReturnType();
            genericReturnType = method.getGenericReturnType();
        }

        if (HttpResult.class.isAssignableFrom(returnType)) {
            final ParameterizedType type = (ParameterizedType) genericReturnType;
            warnIfHttpResponseArgumentExists(type, type);
            return type.getActualTypeArguments()[0];
        } else {
            return genericReturnType;
        }
    }

    private static void warnIfHttpResponseArgumentExists(Type returnType, ParameterizedType type) {
        for (final Type arg : type.getActualTypeArguments()) {
            if (arg instanceof ParameterizedType) {
                warnIfHttpResponseArgumentExists(returnType, (ParameterizedType) arg);
            } else if (arg instanceof Class) {
                final Class<?> clazz = (Class<?>) arg;
                if (HttpResponse.class.isAssignableFrom(clazz) ||
                    AggregatedHttpResponse.class.isAssignableFrom(clazz)) {
                    logger.warn("{} in the return type '{}' may take precedence over {}.",
                                clazz.getSimpleName(), returnType, HttpResult.class.getSimpleName());
                }
            }
        }
    }

    /**
     * A default {@link ResponseConverterFunction}s.
     */
    private static final List<ResponseConverterFunction> defaultResponseConverters =
            ImmutableList.of(new JacksonResponseConverterFunction(),
                             new StringResponseConverterFunction(),
                             new ByteArrayResponseConverterFunction(),
                             new HttpFileResponseConverterFunction());

    /**
     * A response converter implementation which creates an {@link HttpResponse} with
     * the objects published from a {@link Publisher} or {@link Stream}.
     */
    private static final class AggregatedResponseConverterFunction
            implements ResponseConverterFunction {

        private final ResponseConverterFunction responseConverter;

        AggregatedResponseConverterFunction(ResponseConverterFunction responseConverter) {
            this.responseConverter = responseConverter;
        }

        @Override
        @SuppressWarnings("unchecked")
        public HttpResponse convertResponse(ServiceRequestContext ctx,
                                            ResponseHeaders headers,
                                            @Nullable Object result,
                                            HttpHeaders trailers) throws Exception {
            final CompletableFuture<?> f;
            if (result instanceof Publisher) {
                f = collectFrom((Publisher<Object>) result, ctx);
            } else if (result instanceof Stream) {
                f = collectFrom((Stream<Object>) result, ctx.blockingTaskExecutor());
            } else {
                return ResponseConverterFunction.fallthrough();
            }

            assert f != null;
            return HttpResponse.from(f.thenApply(aggregated -> {
                try {
                    return responseConverter.convertResponse(ctx, headers, aggregated, trailers);
                } catch (Exception ex) {
                    return Exceptions.throwUnsafely(ex);
                }
            }));
        }
    }
}
