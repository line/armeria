/*
 *  Copyright 2017 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.common.metric;

import static java.util.Objects.requireNonNull;

import java.util.function.BiFunction;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.client.metric.MetricCollectingClient;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.server.PathMapping;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.metric.MetricCollectingService;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;

/**
 * Creates a {@link MeterIdPrefix} from a {@link RequestLog}.
 *
 * @see MetricCollectingClient
 * @see MetricCollectingService
 */
@FunctionalInterface
public interface MeterIdPrefixFunction {

    /**
     * Returns the default function that creates a {@link MeterIdPrefix} with the specified name and
     * the {@link Tag}s derived from the current {@link PathMapping} (if available) and HTTP (or RPC)
     * method name. e.g. {@code my_service_name{pathMapping="exact:/service/path",method="POST"}}
     */
    static MeterIdPrefixFunction ofDefault(String name) {
        requireNonNull(name, "name");
        return (registry, log) -> {
            final RequestContext ctx = log.context();
            final Object requestContent = log.requestContent();

            String methodName = null;
            if (requestContent instanceof RpcRequest) {
                methodName = ((RpcRequest) requestContent).method();
            }

            if (methodName == null) {
                final HttpHeaders requestHeaders = log.requestHeaders();
                final HttpMethod httpMethod = requestHeaders.method();
                if (httpMethod != null) {
                    methodName = httpMethod.name();
                }
            }

            if (methodName == null) {
                methodName = MoreObjects.firstNonNull(log.method().name(), "__UNKNOWN_METHOD__");
            }

            if (ctx instanceof ServiceRequestContext) {
                final ServiceRequestContext sCtx = (ServiceRequestContext) ctx;
                return new MeterIdPrefix(name,
                                         "hostnamePattern", sCtx.virtualHost().hostnamePattern(),
                                         "pathMapping", sCtx.pathMapping().meterTag(),
                                         "method", methodName);
            } else {
                return new MeterIdPrefix(name, "method", methodName);
            }
        };
    }

    /**
     * Creates a {@link MeterIdPrefix} from the specified {@link RequestLog}.
     */
    MeterIdPrefix apply(MeterRegistry registry, RequestLog log);

    /**
     * Returns a {@link MeterIdPrefixFunction} that returns a newly created {@link MeterIdPrefix} which has
     * the specified label added.
     */
    default MeterIdPrefixFunction withTags(String... keyValues) {
        requireNonNull(keyValues, "keyValues");
        return withTags(Tags.zip(keyValues));
    }

    /**
     * Returns a {@link MeterIdPrefixFunction} that returns a newly created {@link MeterIdPrefix} which has
     * the specified labels added.
     */
    default MeterIdPrefixFunction withTags(Iterable<Tag> tags) {
        requireNonNull(tags, "tags");
        return andThen((registry, id) -> id.withTags(tags));
    }

    /**
     * Returns a {@link MeterIdPrefixFunction} that applies transformation on the {@link MeterIdPrefix}
     * returned by this function.
     */
    default MeterIdPrefixFunction andThen(BiFunction<MeterRegistry, MeterIdPrefix, MeterIdPrefix> function) {
        return (registry, log) -> function.apply(registry, apply(registry, log));
    }
}
