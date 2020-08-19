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
package com.linecorp.armeria.internal.common.metric;

import static com.google.common.base.MoreObjects.firstNonNull;
import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;

import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.logging.RequestOnlyLog;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;

/**
 * Default {@link MeterIdPrefixFunction} implementation.
 */
public final class DefaultMeterIdPrefixFunction implements MeterIdPrefixFunction {

    private final String name;

    public static MeterIdPrefixFunction of(String name) {
        return new DefaultMeterIdPrefixFunction(name);
    }

    private DefaultMeterIdPrefixFunction(String name) {
        this.name = requireNonNull(name, "name");
    }

    @Override
    public MeterIdPrefix activeRequestPrefix(MeterRegistry registry, RequestOnlyLog log) {
        /* hostname.pattern, method, service */
        final Builder<Tag> tagListBuilder = ImmutableList.builderWithExpectedSize(3);
        addActiveRequestPrefixTags(tagListBuilder, log);
        return new MeterIdPrefix(name, tagListBuilder.build());
    }

    @Override
    public MeterIdPrefix completeRequestPrefix(MeterRegistry registry, RequestLog log) {
        /* hostname.pattern, http.status, method, service */
        final Builder<Tag> tagListBuilder = ImmutableList.builderWithExpectedSize(4);
        addCompleteRequestPrefixTags(tagListBuilder, log);
        return new MeterIdPrefix(name, tagListBuilder.build());
    }

    /**
     * Adds the active request tags in lexicographical order for better sort performance.
     * This adds {@code hostname.pattern}, {@code method} and {@code service}, in order.
     */
    public static void addActiveRequestPrefixTags(Builder<Tag> tagListBuilder, RequestOnlyLog log) {
        requireNonNull(tagListBuilder, "tagListBuilder");
        requireNonNull(log, "log");
        addHostnamePattern(tagListBuilder, log);
        addMethodAndService(tagListBuilder, log);
    }

    /**
     * Adds the complete request tags in lexicographical order for better sort performance.
     * This adds {@code hostname.pattern}, {@code http.status}, {@code method} and {@code service}, in order.
     */
    public static void addCompleteRequestPrefixTags(Builder<Tag> tagListBuilder, RequestLog log) {
        requireNonNull(tagListBuilder, "tagListBuilder");
        requireNonNull(log, "log");
        addHostnamePattern(tagListBuilder, log);
        addHttpStatus(tagListBuilder, log);
        addMethodAndService(tagListBuilder, log);
    }

    /**
     * Adds {@code http.status} tag to the {@code tagListBuilder}.
     */
    public static void addHttpStatus(Builder<Tag> tagListBuilder, RequestLog log) {
        requireNonNull(tagListBuilder, "tagListBuilder");
        requireNonNull(log, "log");
        // Add the 'httpStatus' tag.
        final HttpStatus status;
        if (log.isAvailable(RequestLogProperty.RESPONSE_HEADERS)) {
            status = log.responseHeaders().status();
        } else {
            status = HttpStatus.UNKNOWN;
        }
        tagListBuilder.add(Tag.of("http.status", status.codeAsText()));
    }

    private static void addHostnamePattern(Builder<Tag> tagListBuilder, RequestOnlyLog log) {
        final RequestContext ctx = log.context();
        if (ctx instanceof ServiceRequestContext) {
            final ServiceRequestContext sCtx = (ServiceRequestContext) ctx;
            tagListBuilder.add(Tag.of("hostname.pattern", sCtx.config().virtualHost().hostnamePattern()));
        }
    }

    private static void addMethodAndService(Builder<Tag> tagListBuilder, RequestOnlyLog log) {
        tagListBuilder.add(Tag.of("method", log.name()));
        tagListBuilder.add(Tag.of("service", firstNonNull(log.serviceName(), "none")));
    }
}
