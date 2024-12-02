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
import static com.linecorp.armeria.internal.common.metric.DefaultMeterIdPrefixFunction.addHttpStatus;
import static java.util.Objects.requireNonNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestOnlyLog;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;
import com.linecorp.armeria.internal.common.util.TemporaryThreadLocals;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;

/**
 * Client {@link MeterIdPrefixFunction} implementation.
 */
public final class ClientMeterIdPrefixFunction implements MeterIdPrefixFunction {

    private static final Logger logger = LoggerFactory.getLogger(ClientMeterIdPrefixFunction.class);
    private static boolean warnedServiceRequestContext;

    private final String name;
    private final boolean includeHttpStatus;
    private final boolean includeMethod;
    private final boolean includeRemoteAddress;
    private final boolean includeService;

    public ClientMeterIdPrefixFunction(String name, boolean includeHttpStatus, boolean includeMethod,
                                       boolean includeRemoteAddress, boolean includeService) {
        this.name = requireNonNull(name, "name");
        this.includeHttpStatus = includeHttpStatus;
        this.includeMethod = includeMethod;
        this.includeRemoteAddress = includeRemoteAddress;
        this.includeService = includeService;
    }

    @Override
    public MeterIdPrefix activeRequestPrefix(MeterRegistry registry, RequestOnlyLog log) {
        /* method, remoteAddress, service */
        final Builder<Tag> tagListBuilder = ImmutableList.builderWithExpectedSize(3);
        addActiveRequestTags(tagListBuilder, log);
        return new MeterIdPrefix(name, tagListBuilder.build());
    }

    private void addActiveRequestTags(Builder<Tag> tagListBuilder, RequestOnlyLog log) {
        if (includeMethod) {
            tagListBuilder.add(Tag.of("method", log.name()));
        }
        if (includeRemoteAddress) {
            tagListBuilder.add(Tag.of("remoteAddress", remoteAddress(log)));
        }
        if (includeService) {
            tagListBuilder.add(Tag.of("service", firstNonNull(log.serviceName(), "none")));
        }
    }

    private static String remoteAddress(RequestOnlyLog log) {
        final RequestContext context = log.context();
        if (!(context instanceof ClientRequestContext)) {
            if (!warnedServiceRequestContext) {
                warnedServiceRequestContext = true;
                logger.warn("Cannot retrieve remoteAddress from {}", context);
            }
            return "none";
        }

        final ClientRequestContext cCtx = (ClientRequestContext) context;
        final Endpoint endpoint = cCtx.endpoint();
        if (endpoint == null) {
            return "none";
        }

        try (TemporaryThreadLocals acquired = TemporaryThreadLocals.acquire()) {
            final StringBuilder builder = acquired.stringBuilder();
            builder.append(endpoint.host());
            if (endpoint.hasIpAddr() && !endpoint.isIpAddrOnly()) {
                builder.append('/').append(endpoint.ipAddr());
            }
            if (endpoint.hasPort()) {
                builder.append(':').append(endpoint.port());
            }
            return builder.toString();
        }
    }

    @Override
    public MeterIdPrefix completeRequestPrefix(MeterRegistry registry, RequestLog log) {
        /* http.status, method, remoteAddress, service */
        final Builder<Tag> tagListBuilder = ImmutableList.builderWithExpectedSize(4);
        if (includeHttpStatus) {
            addHttpStatus(tagListBuilder, log);
        }
        addActiveRequestTags(tagListBuilder, log);
        return new MeterIdPrefix(name, tagListBuilder.build());
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("name", name)
                          .add("includeHttpStatus", includeHttpStatus)
                          .add("includeMethod", includeMethod)
                          .add("includeRemoteAddress", includeRemoteAddress)
                          .add("includeService", includeService)
                          .toString();
    }
}
