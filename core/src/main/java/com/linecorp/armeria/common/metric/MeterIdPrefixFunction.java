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

import com.linecorp.armeria.client.metric.MetricCollectingClient;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.logging.RequestOnlyLog;
import com.linecorp.armeria.internal.common.metric.DefaultMeterIdPrefixFunction;
import com.linecorp.armeria.server.Route;
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
 */
public interface MeterIdPrefixFunction {

    /**
     * Returns the default function that creates a {@link MeterIdPrefix} with the specified name and
     * the {@link Tag}s derived from the {@link RequestLog} properties.
     * <ul>
     *   <li>Server-side tags:<ul>
     *     <li>{@code hostnamePattern} - {@link VirtualHost#hostnamePattern()}
     *     <li>{@code route} - {@link Route#patternString()}</li>
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
        return DefaultMeterIdPrefixFunction.of(name);
    }

    /**
     * Returns a {@link MeterIdPrefixFunction} which generates a {@link MeterIdPrefix} from the given
     * {@link MeterRegistry} and {@link RequestOnlyLog}.
     * Both {@link #activeRequestPrefix(MeterRegistry, RequestOnlyLog)}
     * and {@link #completeRequestPrefix(MeterRegistry, RequestLog)} will return the same {@link MeterIdPrefix}
     * for the same input.
     */
    static MeterIdPrefixFunction of(
            BiFunction<? super MeterRegistry, ? super RequestOnlyLog, MeterIdPrefix> function) {
        requireNonNull(function, "function");
        return new MeterIdPrefixFunction() {
            @Override
            public MeterIdPrefix activeRequestPrefix(MeterRegistry registry, RequestOnlyLog log) {
                return function.apply(registry, log);
            }

            @Override
            public MeterIdPrefix completeRequestPrefix(MeterRegistry registry, RequestLog log) {
                return function.apply(registry, log);
            }
        };
    }

    /**
     * Creates a {@link MeterIdPrefix} for the active request counter gauges from the specified
     * {@link RequestOnlyLog}. Note that the given {@link RequestOnlyLog} might not have all properties
     * available. However, the following properties' availability is guaranteed:
     * <ul>
     *   <li>{@link RequestLogProperty#REQUEST_START_TIME}</li>
     *   <li>{@link RequestLogProperty#REQUEST_HEADERS}</li>
     *   <li>{@link RequestLogProperty#SESSION}</li>
     *   <li>{@link RequestLogProperty#NAME}</li>
     * </ul>
     */
    MeterIdPrefix activeRequestPrefix(MeterRegistry registry, RequestOnlyLog log);

    /**
     * Creates a {@link MeterIdPrefix} from the specified complete {@link RequestLog}.
     */
    MeterIdPrefix completeRequestPrefix(MeterRegistry registry, RequestLog log);

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
        return andThen((registry, log, meterIdPrefix) -> meterIdPrefix.withTags(tags));
    }

    /**
     * Returns a {@link MeterIdPrefixFunction} that applies transformation on the {@link MeterIdPrefix}
     * returned by this function.
     */
    default MeterIdPrefixFunction andThen(MeterIdPrefixFunctionCustomizer function) {
        requireNonNull(function, "function");
        return new MeterIdPrefixFunction() {
            @Override
            public MeterIdPrefix activeRequestPrefix(MeterRegistry registry, RequestOnlyLog log) {
                return function.apply(registry, log,
                                      MeterIdPrefixFunction.this.activeRequestPrefix(registry, log));
            }

            @Override
            public MeterIdPrefix completeRequestPrefix(MeterRegistry registry, RequestLog log) {
                return function.apply(registry, log,
                                      MeterIdPrefixFunction.this.completeRequestPrefix(registry, log));
            }
        };
    }
}
