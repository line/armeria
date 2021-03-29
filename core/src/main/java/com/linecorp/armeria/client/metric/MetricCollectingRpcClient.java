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

import java.util.function.Function;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import com.linecorp.armeria.client.RpcClient;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Decorates an {@link RpcClient} to collect metrics into {@link MeterRegistry}.
 *
 * <p>Example:
 * <pre>{@code
 * MyService.Iface client =
 *     Clients.builder(uri)
 *            .rpcDecorator(MetricCollectingRpcClient.newDecorator(MeterIdPrefixFunction.ofDefault("myClient")))
 *            .build(MyService.Iface.class);
 * }</pre>
 *
 * <p>It is generally recommended not to use a class or package name as a metric name, because otherwise
 * seemingly harmless refactoring such as rename may break metric collection.
 */
public final class MetricCollectingRpcClient extends AbstractMetricCollectingClient<RpcRequest, RpcResponse>
        implements RpcClient {

    /**
     * Returns an {@link RpcClient} decorator that tracks request stats using {@link MeterRegistry}.
     */
    public static Function<? super RpcClient, MetricCollectingRpcClient> newDecorator(
            MeterIdPrefixFunction meterIdPrefixFunction) {
        requireNonNull(meterIdPrefixFunction, "meterIdPrefixFunction");
        return builder(meterIdPrefixFunction).newDecorator();
    }

    /**
     * Returns a new {@link MetricCollectingRpcClientBuilder} instance.
     */
    public static MetricCollectingRpcClientBuilder builder(MeterIdPrefixFunction meterIdPrefixFunction) {
        return new MetricCollectingRpcClientBuilder(meterIdPrefixFunction);
    }

    MetricCollectingRpcClient(RpcClient delegate, MeterIdPrefixFunction meterIdPrefixFunction,
                              @Nullable Predicate<? super RequestLog> successFunction) {
        super(delegate, meterIdPrefixFunction, successFunction);
    }
}
