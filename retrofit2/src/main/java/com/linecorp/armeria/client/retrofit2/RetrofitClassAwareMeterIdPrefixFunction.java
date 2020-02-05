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

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestOnlyLog;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.internal.common.metric.RequestMetricSupport;

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
 *     <li>{@code service} (or {@code serviceTagName}) - Retrofit service interface name
 *                                                       or provided {@code serviceName}</li>
 *     <li>{@code path}   - Retrofit service interface method path taken from method annotation
 *                          or {@code UNKNOWN} if Retrofit service interface method available</li>
 *     <li>{@code method} - Retrofit service interface method
 *                          or {@code UNKNOWN} if Retrofit service interface method available</li>
 *     <li>{@code http.method} - HTTP method name from Retrofit service interface method annotation
 *                               or from {@link RequestHeaders#method()} if Retrofit service interface
 *                               method not available</li>
 *     <li>{@code http.status} - {@link HttpStatus#code()}</li>
 * </ul>
 */
final class RetrofitClassAwareMeterIdPrefixFunction extends RetrofitMeterIdPrefixFunction {

    private static final List<Class<?>> RETROFIT_ANNOTATIONS = ImmutableList.of(
            POST.class, PUT.class, PATCH.class, HEAD.class, GET.class, OPTIONS.class, HTTP.class, DELETE.class
    );
    private static final String METHOD_TAG_NAME = "method";
    private static final String PATH_TAG_NAME = "path";
    private static final String HTTP_METHOD_TAG_NAME = "http.method";
    private static final List<Tag> DEFAULT_METHOD_TAGS = ImmutableList.of(
            Tag.of(METHOD_TAG_NAME, "UNKNOWN"),
            Tag.of(PATH_TAG_NAME, "UNKNOWN")
    );

    private final Map<String, List<Tag>> methodNameToTags;
    private final String name;
    private final String serviceName;
    private final String serviceTagName;

    RetrofitClassAwareMeterIdPrefixFunction(String name,
                                            @Nullable String serviceTagName,
                                            @Nullable String serviceName,
                                            Class<?> serviceClass) {
        super(name, null, null, null);

        this.name = name;
        this.serviceTagName = firstNonNull(serviceTagName, "service");
        this.serviceName = firstNonNull(serviceName, serviceClass.getSimpleName());
        methodNameToTags = defineTagsForMethods(serviceClass);
    }

    @Override
    public MeterIdPrefix activeRequestPrefix(MeterRegistry registry, RequestOnlyLog log) {
        final ImmutableList.Builder<Tag> tagsListBuilder = ImmutableList.builderWithExpectedSize(4);
        buildTags(tagsListBuilder, log);
        return new MeterIdPrefix(name, tagsListBuilder.build());
    }

    @Override
    public MeterIdPrefix completeRequestPrefix(MeterRegistry registry, RequestLog log) {
        final ImmutableList.Builder<Tag> tagListBuilder = ImmutableList.builderWithExpectedSize(5);
        buildTags(tagListBuilder, log);
        RequestMetricSupport.appendHttpStatusTag(tagListBuilder, log);
        return new MeterIdPrefix(name, tagListBuilder.build());
    }

    private void buildTags(ImmutableList.Builder<Tag> tagsBuilder, RequestOnlyLog log) {
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

        tagsBuilder.add(Tag.of(HTTP_METHOD_TAG_NAME, log.requestHeaders().method().name()));
        tagsBuilder.addAll(DEFAULT_METHOD_TAGS);
    }

    @VisibleForTesting
    static Map<String, List<Tag>> defineTagsForMethods(Class<?> serviceClass) {
        final ImmutableMap.Builder<String, List<Tag>> methodNameToTags = ImmutableMap.builder();

        final Method[] declaredMethods = serviceClass.getDeclaredMethods();
        for (final Method clientServiceMethod : declaredMethods) {
            for (final Annotation annotation : clientServiceMethod.getDeclaredAnnotations()) {
                if (!RETROFIT_ANNOTATIONS.contains(annotation.annotationType())) {
                    continue;
                }

                final ImmutableList.Builder<Tag> tags = ImmutableList.builder();
                tags.addAll(tagsFromAnnotation(annotation));
                tags.add(Tag.of(METHOD_TAG_NAME, clientServiceMethod.getName()));
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
                throw new IllegalStateException("Unexpected retrofit annotation: " +
                                                annotation.annotationType(), e);
            }
            httpMethod = annotation.annotationType().getSimpleName();
        }

        return ImmutableList.of(
                Tag.of(HTTP_METHOD_TAG_NAME, httpMethod),
                Tag.of(PATH_TAG_NAME, httpPath)
        );
    }
}
