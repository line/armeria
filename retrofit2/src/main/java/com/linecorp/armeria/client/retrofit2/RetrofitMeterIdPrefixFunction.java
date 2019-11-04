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

import static java.util.Objects.requireNonNull;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;
import com.linecorp.armeria.internal.metric.RequestMetricSupport;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import retrofit2.Invocation;

/**
 * Returns the default function for retrofit that creates a {@link MeterIdPrefix} with the specified name and
 * the {@link Tag}s derived from the {@link RequestLog} properties and {@link Invocation}.
 * <ul>
 *     <li>{@code service} - Retrofit service interface name or defaultServiceName if Retrofit service interface
 *                           name is not available</li>　
 *     <li>{@code method} - Retrofit service interface method name or {@link HttpMethod#name()} if Retrofit
 *                          service interface name is not available</li>
 *     <li>{@code httpStatus} - {@link HttpStatus#code()}</li>
 * </ul>
 */
public final class RetrofitMeterIdPrefixFunction implements MeterIdPrefixFunction {

    /**
     * Returns a newly created {@link RetrofitMeterIdPrefixFunction} with the specified {@code name}.
     */
    public static RetrofitMeterIdPrefixFunction of(String name) {
        return builder(name).build();
    }

    /**
     * Returns a newly created {@link RetrofitMeterIdPrefixFunctionBuilder} with the specified {@code name}.
     */
    public static RetrofitMeterIdPrefixFunctionBuilder builder(String name) {
        return new RetrofitMeterIdPrefixFunctionBuilder(requireNonNull(name, "name"));
    }

    private final String name;
    @Nullable
    private final String serviceTagName;
    @Nullable
    private final String defaultServiceName;

    RetrofitMeterIdPrefixFunction(String name, @Nullable String serviceTagName,
                                  @Nullable String defaultServiceName) {
        this.name = name;
        if (serviceTagName != null && defaultServiceName != null) {
            this.serviceTagName = serviceTagName;
            this.defaultServiceName = defaultServiceName;
        } else {
            this.serviceTagName = null;
            this.defaultServiceName = null;
        }
    }

    @Override
    public MeterIdPrefix activeRequestPrefix(MeterRegistry registry, RequestLog log) {
        final ImmutableList.Builder<Tag> tagListBuilder = ImmutableList.builderWithExpectedSize(3);
        buildTags(tagListBuilder, log);
        return new MeterIdPrefix(name, tagListBuilder.build());
    }

    @Override
    public MeterIdPrefix apply(MeterRegistry registry, RequestLog log) {
        final ImmutableList.Builder<Tag> tagListBuilder = ImmutableList.builderWithExpectedSize(3);
        buildTags(tagListBuilder, log);
        RequestMetricSupport.appendHttpStatusTag(tagListBuilder, log);
        return new MeterIdPrefix(name, tagListBuilder.build());
    }

    private void buildTags(ImmutableList.Builder<Tag> tagListBuilder, RequestLog log) {
        final Invocation invocation = InvocationUtil.getInvocation(log);
        if (invocation != null) {
            if (serviceTagName != null) {
                tagListBuilder.add(Tag.of(serviceTagName,
                                          invocation.method().getDeclaringClass().getSimpleName()));
            }
            tagListBuilder.add(Tag.of("method", invocation.method().getName()));
        } else {
            if (serviceTagName != null) {
                assert defaultServiceName != null;
                tagListBuilder.add(Tag.of(serviceTagName, defaultServiceName));
            }
            tagListBuilder.add(Tag.of("method", log.method().name()));
        }
    }
}
