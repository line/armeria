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

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.metric.MetricCollectingClient;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.internal.metric.RequestMetricSupport;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.VirtualHost;
import com.linecorp.armeria.server.metric.MetricCollectingService;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;

/**
 * Creates a {@link MeterIdPrefix} from a {@link RequestLog}.
 *
 * @see MetricCollectingClient
 * @see MetricCollectingService
 * @see MetricCollectingRpcService
 */
@FunctionalInterface
public interface MeterIdPrefixFunction {

    /**
     * Returns the default function that creates a {@link MeterIdPrefix} with the specified name and
     * the {@link Tag}s derived from the {@link RequestLog} properties.
     * <ul>
     *   <li>Server-side tags:<ul>
     *     <li>{@code hostnamePattern} - {@link VirtualHost#hostnamePattern()}
     *     <li>{@code route} - {@link Route#meterTag()}</li>
     *     <li>{@code method} - RPC method name or {@link HttpMethod#name()} if RPC method name is not
     *                          available</li>
     *     <li>{@code httpStatus} - {@link HttpStatus#code()}</li>
     *   </ul></li>
     *   <li>Client-side tags:<ul>
     *     <li>{@code method} - RPC method name or {@link HttpMethod#name()} if RPC method name is not
     *                          available</li>
     *     <li>{@code httpStatus} - {@link HttpStatus#code()}</li>
     *   </ul></li>
     * </ul>
     */
    static MeterIdPrefixFunction ofDefault(String name) {
        requireNonNull(name, "name");
        return new MeterIdPrefixFunction() {
            @Override
            public MeterIdPrefix activeRequestPrefix(MeterRegistry registry, RequestLog log) {
                // hostNamePattern, method, route
                final ImmutableList.Builder<Tag> tagListBuilder = ImmutableList.builderWithExpectedSize(3);
                // For optimal performance, add tags in order.
                buildTags(tagListBuilder, log, false);
                return new MeterIdPrefix(name, tagListBuilder.build());
            }

            @Override
            public MeterIdPrefix apply(MeterRegistry registry, RequestLog log) {
                // hostNamePattern, httpStatus, method, route
                final ImmutableList.Builder<Tag> tagListBuilder = ImmutableList.builderWithExpectedSize(4);
                // For optimal performance, add tags in order.
                buildTags(tagListBuilder, log, true);
                return new MeterIdPrefix(name, tagListBuilder.build());
            }

            private void buildTags(ImmutableList.Builder<Tag> tagListBuilder, RequestLog log,
                                   boolean addStatus) {
                final RequestContext ctx = log.context();
                final Object requestContent = log.requestContent();

                String methodName = null;
                if (requestContent instanceof RpcRequest) {
                    methodName = ((RpcRequest) requestContent).method();
                }

                if (methodName == null) {
                    final RequestHeaders requestHeaders = log.requestHeaders();
                    methodName = requestHeaders.method().name();
                }

                if (ctx instanceof ServiceRequestContext) {
                    final ServiceRequestContext sCtx = (ServiceRequestContext) ctx;
                    tagListBuilder.add(Tag.of("hostnamePattern", sCtx.virtualHost().hostnamePattern()));
                }

                if (addStatus) {
                    RequestMetricSupport.appendHttpStatusTag(tagListBuilder, log);
                }

                tagListBuilder.add(Tag.of("method", methodName));

                if (ctx instanceof ServiceRequestContext) {
                    final ServiceRequestContext sCtx = (ServiceRequestContext) ctx;
                    tagListBuilder.add(Tag.of("route", sCtx.route().meterTag()));
                }
            }
        };
    }

    /**
     * Creates a {@link MeterIdPrefix} from the specified {@link RequestLog}.
     */
    MeterIdPrefix apply(MeterRegistry registry, RequestLog log);

    /**
     * Creates a {@link MeterIdPrefix} for the active request counter gauges from the specified
     * {@link RequestLog}. This method by default delegates to {@link #apply(MeterRegistry, RequestLog)}.
     * You must override this method if your {@link #apply(MeterRegistry, RequestLog)} implementation builds
     * a {@link MeterIdPrefix} using response properties that's not always available when the active request
     * counter is increased, such as HTTP status.
     */
    default MeterIdPrefix activeRequestPrefix(MeterRegistry registry, RequestLog log) {
        return apply(registry, log);
    }

    /**
     * Returns a {@link MeterIdPrefixFunction} that returns a newly created {@link MeterIdPrefix} which has
     * the specified label added.
     */
    default MeterIdPrefixFunction withTags(String... keyValues) {
        requireNonNull(keyValues, "keyValues");
        return withTags(Tags.of(keyValues));
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
        return new MeterIdPrefixFunction() {
            @Override
            public MeterIdPrefix activeRequestPrefix(MeterRegistry registry, RequestLog log) {
                return function.apply(registry, MeterIdPrefixFunction.this.activeRequestPrefix(registry, log));
            }

            @Override
            public MeterIdPrefix apply(MeterRegistry registry, RequestLog log) {
                return function.apply(registry, MeterIdPrefixFunction.this.apply(registry, log));
            }
        };
    }
}
