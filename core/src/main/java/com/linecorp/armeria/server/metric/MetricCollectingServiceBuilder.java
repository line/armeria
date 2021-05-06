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
package com.linecorp.armeria.server.metric;

import static java.util.Objects.requireNonNull;

import java.util.function.BiPredicate;
import java.util.function.Function;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.metric.AbstractMetricCollectingBuilder;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;
import com.linecorp.armeria.server.HttpService;

/**
 * Builds a {@link MetricCollectingService} instance.
 */
public final class MetricCollectingServiceBuilder extends AbstractMetricCollectingBuilder {

    MetricCollectingServiceBuilder(MeterIdPrefixFunction meterIdPrefixFunction) {
        super(meterIdPrefixFunction);
    }

    @Override
    public MetricCollectingServiceBuilder successFunction(
            BiPredicate<? super RequestContext, ? super RequestLog> successFunction) {
        return (MetricCollectingServiceBuilder) super.successFunction(successFunction);
    }

    /**
     * Returns a newly-created {@link MetricCollectingService} decorating {@link HttpService} based
     * on the properties of this builder.
     */
    public MetricCollectingService build(HttpService delegate) {
        requireNonNull(delegate, "delegate");
        return new MetricCollectingService(delegate, meterIdPrefixFunction(), successFunction());
    }

    /**
     * Returns a newly-created {@link MetricCollectingService} decorator based
     * on the properties of this builder and applies {@link MeterIdPrefixFunction}.
     */
    public Function<? super HttpService, MetricCollectingService> newDecorator() {
        return this::build;
    }
}
