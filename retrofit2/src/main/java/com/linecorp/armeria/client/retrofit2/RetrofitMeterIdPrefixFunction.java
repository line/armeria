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

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAvailability;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;

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

    public static final class RetrofitMeterIdPrefixFunctionBuilder {

        private final String name;
        @Nullable
        private String tagName;
        @Nullable
        private String defaultServiceName;

        private RetrofitMeterIdPrefixFunctionBuilder(String name) {
            this.name = name;
        }

        /**
         * Make tag of {@link RetrofitMeterIdPrefixFunction} contains Retrofit service interface name.
         */
        public RetrofitMeterIdPrefixFunctionBuilder withServiceTag(String tagName, String defaultServiceName) {
            this.tagName = tagName;
            this.defaultServiceName = defaultServiceName;
            return this;
        }

        public RetrofitMeterIdPrefixFunction build() {
            return new RetrofitMeterIdPrefixFunction(name, tagName, defaultServiceName);
        }
    }

    /**
     * Creates a {@link RetrofitMeterIdPrefixFunctionBuilder} with {@code name}.
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
        return new MeterIdPrefix(name, buildTags(log));
    }

    @Override
    public MeterIdPrefix apply(MeterRegistry registry, RequestLog log) {
        final List<Tag> tags = buildTags(log);
        // Add the 'httpStatus' tag.
        final HttpStatus status;
        if (log.isAvailable(RequestLogAvailability.RESPONSE_HEADERS)) {
            status = log.status();
        } else {
            status = HttpStatus.UNKNOWN;
        }
        tags.add(Tag.of("httpStatus", status.codeAsText()));

        return new MeterIdPrefix(name, tags);
    }

    private List<Tag> buildTags(RequestLog log) {
        final List<Tag> tags = new ArrayList<>(3);
        final Invocation invocation = InvocationUtil.getInvocation(log);
        if (invocation != null) {
            if (serviceTagName != null) {
                tags.add(Tag.of(serviceTagName, invocation.method().getDeclaringClass().getSimpleName()));
            }
            tags.add(Tag.of("method", invocation.method().getName()));
        } else {
            if (serviceTagName != null) {
                assert defaultServiceName != null;
                tags.add(Tag.of(serviceTagName, defaultServiceName));
            }
            tags.add(Tag.of("method", log.method().name()));
        }
        return tags;
    }
}
