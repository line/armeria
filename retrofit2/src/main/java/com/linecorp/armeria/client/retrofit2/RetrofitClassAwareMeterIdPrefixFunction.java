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

import static com.google.common.base.MoreObjects.firstNonNull;
import static java.util.Objects.requireNonNull;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;

import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;
import com.linecorp.armeria.internal.metric.RequestMetricSupport;

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
 * the {@link Tag}s derived from the {@link RequestLog} properties, {@link Invocation} and provided retrofit
 * client.
 * <ul>
 *     <li>{@code service(or serviceTagName} - Retrofit service interface name</li>
 *     <li>{@code path}   - Retrofit service interface method path taken from method annotation</li>
 *     <li>{@code method} - Retrofit service interface method</li>
 *     <li>{@code httpMethod} - HTTP method name from Retrofit service interface method annotation</li>
 *     <li>{@code httpStatus} - {@link HttpStatus#code()}</li>
 * </ul>
 */
public class RetrofitClassAwareMeterIdPrefixFunction implements MeterIdPrefixFunction {
    private static final List<Class> retrofitAnnotations = ImmutableList.of(
            POST.class, PUT.class, PATCH.class, HEAD.class, GET.class, OPTIONS.class, HTTP.class, DELETE.class
    );
    private final Map<String, List<Tag>> methodNameToTags;
    private final String name;
    private final String serviceName;
    private final String serviceTagName;

    /**
     * Returns a newly created {@link RetrofitClassAwareMeterIdPrefixFunction} with the specified {@code name}
     * and {@code serviceClass}.
     */
    public static MeterIdPrefixFunction of(String name, Class serviceClass) {
        return builder(name, serviceClass).build();
    }

    /**
     * Returns a newly created {@link RetrofitMeterIdPrefixFunctionBuilder} with the specified {@code name}
     * and {@code serviceClass}.
     */
    public static RetrofitMeterIdPrefixFunctionBuilder builder(String name, Class serviceClass) {
        return new RetrofitMeterIdPrefixFunctionBuilder(requireNonNull(name, "name"))
                .withServiceClass(serviceClass);
    }

    RetrofitClassAwareMeterIdPrefixFunction(String name,
                                            Class serviceClass,
                                            String serviceTagName) {
        this.name = name;
        this.serviceTagName = firstNonNull(serviceTagName, "service");
        this.serviceName = serviceClass.getSimpleName();
        this.methodNameToTags = defineTagsForMethods(serviceClass);
    }

    @Override
    public MeterIdPrefix activeRequestPrefix(MeterRegistry registry, RequestLog log) {
        final ImmutableList.Builder<Tag> tagsListBuilder = ImmutableList.builder();
        buildTags(tagsListBuilder, log);
        return new MeterIdPrefix(name, tagsListBuilder.build());
    }

    @Override
    public MeterIdPrefix apply(MeterRegistry registry, RequestLog log) {
        final ImmutableList.Builder<Tag> tagListBuilder = ImmutableList.builder();
        buildTags(tagListBuilder, log);
        RequestMetricSupport.appendHttpStatusTag(tagListBuilder, log);
        return new MeterIdPrefix(name, tagListBuilder.build());
    }

    private void buildTags(ImmutableList.Builder<Tag> tagsBuilder, RequestLog log) {
        final Invocation invocation = InvocationUtil.getInvocation(log);

        tagsBuilder.add(Tag.of(serviceTagName, serviceName));
        if (invocation != null) {
            final String methodName = invocation.method().getName();
            final List<Tag> methodTags = methodNameToTags.get(methodName);
            if (methodTags != null) {
                tagsBuilder.addAll(methodTags);
                return;
            }
        }

        tagsBuilder.add(Tag.of("method", log.method().name()));
    }

    @VisibleForTesting
    static Map<String, List<Tag>> defineTagsForMethods(Class serviceClass) {
        final Builder<String, List<Tag>> methodNameToTags = ImmutableMap.builder();

        final Method[] declaredMethods = serviceClass.getDeclaredMethods();
        for (final Method clientServiceMethod : declaredMethods) {
            final Annotation[] declaredAnnotations = clientServiceMethod.getDeclaredAnnotations();
            if (declaredAnnotations.length == 0) {
                continue;
            }

            for (final Annotation annotation : declaredAnnotations) {
                if (!retrofitAnnotations.contains(annotation.annotationType())) {
                    continue;
                }

                final ImmutableList.Builder<Tag> tags = ImmutableList.builder();
                tags.addAll(tagsFromAnnotation(annotation));
                tags.add(Tag.of("method", clientServiceMethod.getName()));
                methodNameToTags.put(clientServiceMethod.getName(), tags.build());
            }
        }

        return methodNameToTags.build();
    }

    private static List<Tag> tagsFromAnnotation(Annotation annotation) {
        final String httpMethod;
        final String httpPath;
        if (annotation.annotationType().equals(HTTP.class)) {
            final HTTP http = (HTTP) annotation;
            httpMethod = http.method();
            httpPath = http.path();
        } else {
            final Method valueMethod;
            try {
                valueMethod = annotation.getClass().getMethod("value");
                httpPath = (String) valueMethod.invoke(annotation);
            } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                // Should never happen on valid Retrofit client.
                throw new IllegalStateException("Provided Retrofit client have unexpected annotation: " +
                                                annotation.annotationType(), e);
            }
            httpMethod = annotation.annotationType().getSimpleName();
        }

        return ImmutableList.of(
            Tag.of("httpMethod", httpMethod),
            Tag.of("path", httpPath)
        );
    }
}
