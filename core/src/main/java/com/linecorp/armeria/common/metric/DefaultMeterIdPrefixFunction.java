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
package com.linecorp.armeria.common.metric;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.logging.RequestOnlyLog;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;

/**
 * Default {@link MeterIdPrefixFunction} implementation.
 */
public class DefaultMeterIdPrefixFunction implements MeterIdPrefixFunction {

    private final String name;
    private final int activeRequestPrefixSize;
    private final int completeRequestPrefixSize;

    /**
     * Creates a new instance.
     */
    protected DefaultMeterIdPrefixFunction(String name) {
        this(name,
             3, /* hostname.pattern, method, service */
             4  /* hostname.pattern, http.status, method, service */);
    }

    /**
     * Creates a new instance.
     */
    protected DefaultMeterIdPrefixFunction(String name, int activeRequestPrefixSize,
                                           int completeRequestPrefixSize) {
        this.name = requireNonNull(name, "name");
        checkArgument(activeRequestPrefixSize > 0, "activeRequestPrefixSize: %s (expected: > 0)",
                      activeRequestPrefixSize);
        checkArgument(completeRequestPrefixSize > 0, "completeRequestPrefixSize: %s (expected: > 0)",
                      completeRequestPrefixSize);
        this.activeRequestPrefixSize = activeRequestPrefixSize;
        this.completeRequestPrefixSize = completeRequestPrefixSize;
    }

    @Override
    public MeterIdPrefix activeRequestPrefix(MeterRegistry registry, RequestOnlyLog log) {
        final Builder<Tag> tagListBuilder =
                ImmutableList.builderWithExpectedSize(activeRequestPrefixSize);
        addActiveRequestPrefixTags(tagListBuilder, log);
        return new MeterIdPrefix(name, tagListBuilder.build());
    }

    @Override
    public MeterIdPrefix completeRequestPrefix(MeterRegistry registry, RequestLog log) {
        final Builder<Tag> tagListBuilder =
                ImmutableList.builderWithExpectedSize(completeRequestPrefixSize);
        addCompleteRequestPrefixTags(tagListBuilder, log);
        return new MeterIdPrefix(name, tagListBuilder.build());
    }

    /**
     * Adds the active request tags in lexicographical order for better sort performance.
     */
    protected void addActiveRequestPrefixTags(
            Builder<Tag> tagListBuilder, RequestOnlyLog log) {
        addHostnamePattern(tagListBuilder, log);
        addMethodAndService(tagListBuilder, log);
    }

    /**
     * Adds the complete request tags in lexicographical order for better sort performance.
     */
    protected void addCompleteRequestPrefixTags(Builder<Tag> tagListBuilder, RequestLog log) {
        addHostnamePattern(tagListBuilder, log);
        addHttpStatus(tagListBuilder, log);
        addMethodAndService(tagListBuilder, log);
    }

    private static void addHostnamePattern(Builder<Tag> tagListBuilder, RequestOnlyLog log) {
        final RequestContext ctx = log.context();
        if (ctx instanceof ServiceRequestContext) {
            final ServiceRequestContext sCtx = (ServiceRequestContext) ctx;
            tagListBuilder.add(Tag.of(Flags.useLegacyMeterNames() ? "hostnamePattern"
                                                                  : "hostname.pattern",
                                      sCtx.config().virtualHost().hostnamePattern()));
        }
    }

    private static void addMethodAndService(Builder<Tag> tagListBuilder, RequestOnlyLog log) {
        String methodName = log.name();
        if (methodName == null) {
            final RequestHeaders requestHeaders = log.requestHeaders();
            methodName = requestHeaders.method().name();
        }
        tagListBuilder.add(Tag.of("method", methodName));
        final String serviceName = log.serviceName();
        if (serviceName != null) {
            tagListBuilder.add(Tag.of("service", serviceName));
        }
    }

    /**
     * Adds {@link HttpStatus} to {@link Tag}.
     */
    protected static void addHttpStatus(Builder<Tag> tagListBuilder, RequestLog log) {
        requireNonNull(tagListBuilder, "tagListBuilder");
        requireNonNull(log, "log");
        // Add the 'httpStatus' tag.
        final HttpStatus status;
        if (log.isAvailable(RequestLogProperty.RESPONSE_HEADERS)) {
            status = log.responseHeaders().status();
        } else {
            status = HttpStatus.UNKNOWN;
        }
        tagListBuilder.add(Tag.of(Flags.useLegacyMeterNames() ? "httpStatus" : "http.status",
                                  status.codeAsText()));
    }
}
