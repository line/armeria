/*
 * Copyright 2019 LINE Corporation
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

import javax.annotation.Nullable;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.SimpleDecoratingClient;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;
import com.linecorp.armeria.internal.common.metric.RequestMetricSupport;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.util.AttributeKey;

/**
 * Decorates a {@link Client} to collect metrics into {@link MeterRegistry}.
 */
abstract class AbstractMetricCollectingClient<I extends Request, O extends Response>
        extends SimpleDecoratingClient<I, O> {

    // A variable to make sure setup method is not called twice.
    private static final AttributeKey<Boolean> REQUEST_METRICS_SET =
            AttributeKey.valueOf(AbstractMetricCollectingClient.class, "REQUEST_METRICS_SET");

    private final MeterIdPrefixFunction meterIdPrefixFunction;
    @Nullable
    private final BiPredicate<? super RequestContext, ? super RequestLog> successFunction;

    AbstractMetricCollectingClient(
            Client<I, O> delegate, MeterIdPrefixFunction meterIdPrefixFunction,
            @Nullable BiPredicate<? super RequestContext, ? super RequestLog> successFunction) {
        super(delegate);
        this.meterIdPrefixFunction = requireNonNull(meterIdPrefixFunction, "meterIdPrefixFunction");
        this.successFunction = successFunction;
    }

    @Override
    public final O execute(ClientRequestContext ctx, I req) throws Exception {
        RequestMetricSupport.setup(ctx, REQUEST_METRICS_SET, meterIdPrefixFunction, false, successFunction);
        return unwrap().execute(ctx, req);
    }
}
