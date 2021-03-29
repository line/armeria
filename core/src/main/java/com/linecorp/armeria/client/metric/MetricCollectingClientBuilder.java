/*
 * Copyright 2021 LINE Corporation
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
package com.linecorp.armeria.client.metric;

import static java.util.Objects.requireNonNull;

import java.util.function.BiPredicate;
import java.util.function.Function;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.metric.AbstractMetricCollectingBuilder;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;

/**
 * Builds a {@link MetricCollectingClient} instance.
 */
public final class MetricCollectingClientBuilder extends AbstractMetricCollectingBuilder {

    MetricCollectingClientBuilder(MeterIdPrefixFunction meterIdPrefixFunction) {
        super(meterIdPrefixFunction);
    }

    @Override
    public MetricCollectingClientBuilder successFunction(
            BiPredicate<? super RequestContext, ? super RequestLog> successFunction) {
        return (MetricCollectingClientBuilder) super.successFunction(successFunction);
    }

    /**
     * Returns a newly-created {@link MetricCollectingClient} decorating {@link HttpClient} based
     * on the properties of this builder.
     */
    public MetricCollectingClient build(HttpClient delegate) {
        requireNonNull(delegate, "delegate");
        return new MetricCollectingClient(delegate, meterIdPrefixFunction(), successFunction());
    }

    /**
     * Returns a newly-created {@link MetricCollectingClient} decorator based
     * on the properties of this builder and applies {@link MeterIdPrefixFunction}.
     */
    public Function<? super HttpClient, MetricCollectingClient> newDecorator() {
        return this::build;
    }
}
