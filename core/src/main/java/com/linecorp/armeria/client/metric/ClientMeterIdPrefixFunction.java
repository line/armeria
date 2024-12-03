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
package com.linecorp.armeria.client.metric;

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestOnlyLog;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunctionCustomizer;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;

/**
 * Creates a {@link MeterIdPrefix} for a client from a {@link RequestLog}.
 *
 * @see MetricCollectingClient
 */
public interface ClientMeterIdPrefixFunction extends MeterIdPrefixFunction {

    /**
     * Returns the function that creates a {@link MeterIdPrefix} with the specified name and
     * the {@link Tag}s derived from the {@link RequestLog} properties.
     * <ul>
     *   <li>{@code method} - RPC method name or {@link HttpMethod#name()} if RPC method name is not
     *                        available</li>
     *   <li>{@code service} - RPC service name or innermost service class name</li>
     *   <li>{@code httpStatus} - {@link HttpStatus#code()}</li>
     * </ul>
     */
    static ClientMeterIdPrefixFunction of(String name) {
        return new ClientMeterIdPrefixFunctionBuilder(name).build();
    }

    /**
     * Returns a {@link ClientMeterIdPrefixFunctionBuilder} with the specified name.
     */
    static ClientMeterIdPrefixFunctionBuilder builder(String name) {
        return new ClientMeterIdPrefixFunctionBuilder(name);
    }

    /**
     * Returns a {@link ClientMeterIdPrefixFunction} that returns a newly created {@link MeterIdPrefix}
     * which has the specified label added.
     */
    @Override
    default ClientMeterIdPrefixFunction withTags(String... keyValues) {
        requireNonNull(keyValues, "keyValues");
        return withTags(Tags.of(keyValues));
    }

    /**
     * Returns a {@link ClientMeterIdPrefixFunction} that returns a newly created {@link MeterIdPrefix}
     * which has the specified labels added.
     */
    @Override
    default ClientMeterIdPrefixFunction withTags(Tag... tags) {
        requireNonNull(tags, "tags");
        return withTags(Tags.of(tags));
    }

    /**
     * Returns a {@link ClientMeterIdPrefixFunction} that returns a newly created {@link MeterIdPrefix}
     * which has the specified labels added.
     */
    @Override
    default ClientMeterIdPrefixFunction withTags(Iterable<Tag> tags) {
        requireNonNull(tags, "tags");
        return andThen((registry, log, meterIdPrefix) -> meterIdPrefix.withTags(tags));
    }

    /**
     * Returns a {@link ClientMeterIdPrefixFunction} that applies transformation on the {@link MeterIdPrefix}
     * returned by this function.
     */
    @Override
    default ClientMeterIdPrefixFunction andThen(MeterIdPrefixFunctionCustomizer function) {
        requireNonNull(function, "function");
        return new ClientMeterIdPrefixFunction() {
            @Override
            public MeterIdPrefix activeRequestPrefix(MeterRegistry registry, RequestOnlyLog log) {
                return function.apply(registry, log,
                                      ClientMeterIdPrefixFunction.this.activeRequestPrefix(registry, log));
            }

            @Override
            public MeterIdPrefix completeRequestPrefix(MeterRegistry registry, RequestLog log) {
                return function.apply(registry, log,
                                      ClientMeterIdPrefixFunction.this.completeRequestPrefix(registry, log));
            }
        };
    }
}
