/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.armeria.client.retrofit2;

import static com.linecorp.armeria.internal.common.metric.DefaultMeterIdPrefixFunction.addHttpStatus;
import static java.util.Objects.requireNonNull;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.MapMaker;

import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestOnlyLog;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import retrofit2.Invocation;
import retrofit2.http.DELETE;
import retrofit2.http.GET;
import retrofit2.http.HEAD;
import retrofit2.http.HTTP;
import retrofit2.http.OPTIONS;
import retrofit2.http.PATCH;
import retrofit2.http.POST;
import retrofit2.http.PUT;

/**
 * Returns the default function for retrofit that creates a {@link MeterIdPrefix} with the specified name and
 * the {@link Tag}s derived from the {@link RequestLog} properties and {@link Invocation}.
 * <ul>
 *     <li>{@code service} (or {@code serviceTagName}) - Retrofit service interface name, provided
 *                                                       {@code serviceName} or {@code UNKNOWN} if Retrofit
 *                                                       service interface method available</li>
 *     <li>{@code path}   - Retrofit service interface method path taken from method annotation
 *                          or {@link RequestHeaders#path()} if Retrofit service interface method available</li>
 *     <li>{@code method} - Retrofit service interface method
 *                          or {@code UNKNOWN} if Retrofit service interface method available</li>
 *     <li>{@code http.method} - HTTP method name from Retrofit service interface method annotation
 *                               or from {@link RequestHeaders#method()} if Retrofit service interface
 *                               method not available</li>
 *     <li>{@code http.status} - {@link HttpStatus#code()}</li>
 * </ul>
 */
public final class RetrofitMeterIdPrefixFunction implements MeterIdPrefixFunction {

    private static final List<Class<?>> RETROFIT_ANNOTATIONS = ImmutableList.of(
            POST.class, PUT.class, PATCH.class, HEAD.class, GET.class, OPTIONS.class, HTTP.class, DELETE.class);

    private static final Map<Method, String> pathCache = new MapMaker().weakKeys().makeMap();

    private static final String NONE = "none";

    /**
     * Returns a newly created {@link RetrofitMeterIdPrefixFunction} with the specified {@code name}.
     */
    public static RetrofitMeterIdPrefixFunction of(String name) {
        return new RetrofitMeterIdPrefixFunction(name);
    }

    private final String name;

    private RetrofitMeterIdPrefixFunction(String name) {
        this.name = requireNonNull(name, "name");
    }

    @Override
    public MeterIdPrefix activeRequestPrefix(MeterRegistry registry, RequestOnlyLog log) {
        /* http.method, method, path, service */
        final Builder<Tag> tagListBuilder = ImmutableList.builderWithExpectedSize(4);
        final RequestHeaders requestHeaders = log.requestHeaders();
        final String httpMethod = requestHeaders.method().name();
        tagListBuilder.add(Tag.of("http.method", httpMethod));
        addMethodPathAndService(tagListBuilder, log, requestHeaders);
        return new MeterIdPrefix(name, tagListBuilder.build());
    }

    @Override
    public MeterIdPrefix completeRequestPrefix(MeterRegistry registry, RequestLog log) {
        /* http.method, http.status, method, path, service */
        final Builder<Tag> tagListBuilder = ImmutableList.builderWithExpectedSize(5);
        final RequestHeaders requestHeaders = log.requestHeaders();
        final String httpMethod = requestHeaders.method().name();
        tagListBuilder.add(Tag.of("http.method", httpMethod));
        addHttpStatus(tagListBuilder, log);
        addMethodPathAndService(tagListBuilder, log, requestHeaders);
        return new MeterIdPrefix(name, tagListBuilder.build());
    }

    private static void addMethodPathAndService(Builder<Tag> tagListBuilder, RequestOnlyLog log,
                                                RequestHeaders requestHeaders) {
        final String methodName;
        final String serviceName;
        final String path;

        final Invocation invocation = InvocationUtil.getInvocation(log);
        if (invocation != null) {
            final Method method = invocation.method();
            methodName = method.getName();
            serviceName = method.getDeclaringClass().getName();
            path = getPathFromMethod(method);
        } else {
            methodName = requestHeaders.method().name();
            serviceName = NONE;
            path = requestHeaders.path();
        }

        tagListBuilder.add(Tag.of("method", methodName));
        tagListBuilder.add(Tag.of("path", path));
        tagListBuilder.add(Tag.of("service", serviceName));
    }

    @VisibleForTesting
    static String getPathFromMethod(Method method) {
        final String path = pathCache.get(method);
        if (path != null) {
            return path;
        }

        return pathCache.computeIfAbsent(method, key -> {
            for (Annotation annotation : method.getDeclaredAnnotations()) {
                if (!RETROFIT_ANNOTATIONS.contains(annotation.annotationType())) {
                    continue;
                }
                if (annotation.annotationType().equals(HTTP.class)) {
                    final HTTP http = (HTTP) annotation;
                    return http.path();
                } else {
                    final Method valueMethod;
                    try {
                        valueMethod = annotation.annotationType().getMethod("value");
                        return (String) valueMethod.invoke(annotation);
                    } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                        // Should never happen on valid Retrofit client.
                        throw new IllegalStateException("Unexpected retrofit annotation: " +
                                                        annotation.annotationType(), e);
                    }
                }
            }
            // Should never reach here.
            return NONE;
        });
    }
}
