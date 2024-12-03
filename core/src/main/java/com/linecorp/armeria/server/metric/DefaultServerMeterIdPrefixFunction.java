/*
 * Copyright 2024 LINE Corporation
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
package com.linecorp.armeria.server.metric;

import static com.linecorp.armeria.internal.common.metric.DefaultMeterIdPrefixFunction.addHttpStatus;
import static com.linecorp.armeria.internal.common.metric.DefaultMeterIdPrefixFunction.addMethodAndService;
import static java.util.Objects.requireNonNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestOnlyLog;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;

final class DefaultServerMeterIdPrefixFunction implements ServerMeterIdPrefixFunction {

    private static final Logger logger = LoggerFactory.getLogger(DefaultServerMeterIdPrefixFunction.class);
    private static boolean warnedClientRequestContext;

    private final String name;
    private final boolean includeHostnamePattern;
    private final boolean includeHttpStatus;
    private final boolean includeMethod;
    private final boolean includeService;

    DefaultServerMeterIdPrefixFunction(String name, boolean includeHostnamePattern, boolean includeHttpStatus,
                                       boolean includeMethod, boolean includeService) {
        this.name = requireNonNull(name, "name");
        this.includeHostnamePattern = includeHostnamePattern;
        this.includeHttpStatus = includeHttpStatus;
        this.includeMethod = includeMethod;
        this.includeService = includeService;
    }

    @Override
    public MeterIdPrefix activeRequestPrefix(MeterRegistry registry, RequestOnlyLog log) {
        /* hostname.pattern, method, service */
        final Builder<Tag> tagListBuilder = ImmutableList.builderWithExpectedSize(3);
        // Add tags in lexicographical order of the tag key.
        if (includeHostnamePattern) {
            tagListBuilder.add(Tag.of("hostname.pattern", hostnamePattern(log)));
        }
        addMethodAndService(tagListBuilder, log, includeMethod, includeService);
        return new MeterIdPrefix(name, tagListBuilder.build());
    }

    private static String hostnamePattern(RequestOnlyLog log) {
        final RequestContext ctx = log.context();
        if (!(ctx instanceof ServiceRequestContext)) {
            if (!warnedClientRequestContext) {
                warnedClientRequestContext = true;
                logger.warn("Cannot retrieve hostnamePattern from {}", ctx);
            }
            return "none";
        }
        return ((ServiceRequestContext) ctx).config().virtualHost().hostnamePattern();
    }

    @Override
    public MeterIdPrefix completeRequestPrefix(MeterRegistry registry, RequestLog log) {
        /* hostname.pattern, http.status, method, service */
        final Builder<Tag> tagListBuilder = ImmutableList.builderWithExpectedSize(4);
        // Add tags in lexicographical order of the tag key.
        if (includeHostnamePattern) {
            tagListBuilder.add(Tag.of("hostname.pattern", hostnamePattern(log)));
        }
        if (includeHttpStatus) {
            addHttpStatus(tagListBuilder, log);
        }
        addMethodAndService(tagListBuilder, log, includeMethod, includeService);
        return new MeterIdPrefix(name, tagListBuilder.build());
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("name", name)
                          .add("includeHttpStatus", includeHttpStatus)
                          .add("includeMethod", includeMethod)
                          .add("includeHostnamePattern", includeHostnamePattern)
                          .add("includeService", includeService)
                          .toString();
    }
}
