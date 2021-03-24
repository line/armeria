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

import java.util.function.Function;

import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;
import com.linecorp.armeria.server.HttpService;

public final class MetricCollectingServiceBuilder {

    private Function<? super RequestLog, Boolean> isSuccess = log -> false;
    // TODO - what should be the default meter prefix function?
    private MeterIdPrefixFunction meterIdPrefixFunction = MeterIdPrefixFunction.ofDefault("default");

    MetricCollectingServiceBuilder() { }

    public MetricCollectingServiceBuilder isSuccess(Function<? super RequestLog, Boolean> isSuccess) {
        this.isSuccess = requireNonNull(isSuccess, "isSuccess");
        return this;
    }

    public MetricCollectingServiceBuilder meterIdPrefix(MeterIdPrefixFunction meterIdPrefixFunction) {
        this.meterIdPrefixFunction = requireNonNull(meterIdPrefixFunction, "meterIdPrefixFunction");
        return this;
    }

    /**
     * Returns a newly-created {@link MetricCollectingService} decorating {@link HttpService} based
     * on the properties of this builder.
     */
    public MetricCollectingService build(HttpService delegate) {
        return new MetricCollectingService(delegate, meterIdPrefixFunction, isSuccess);
    }

    /**
     * Returns a newly-created {@link MetricCollectingService} decorator based
     * on the properties of this builder.
     */
    public Function<? super HttpService, MetricCollectingService> newDecorator() {
        return this::build;
    }

    /**
     * Returns a newly-created {@link MetricCollectingService} decorator based
     * on the properties of this builder and applies {@link MeterIdPrefixFunction}.
     */
    public Function<? super HttpService, MetricCollectingService> newDecorator(
            MeterIdPrefixFunction meterIdPrefixFunction) {
        meterIdPrefix(meterIdPrefixFunction);
        return this::build;
    }
}
