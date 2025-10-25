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
package com.linecorp.armeria.client.endpoint;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.DefaultEndpointSelector.LoadBalancerFactory;
import com.linecorp.armeria.common.loadbalancer.LoadBalancer;
import com.linecorp.armeria.common.loadbalancer.UpdatableLoadBalancer;
import com.linecorp.armeria.common.loadbalancer.WeightTransition;
import com.linecorp.armeria.common.util.Ticker;

import io.netty.util.concurrent.EventExecutor;

/**
 * A ramping up {@link EndpointSelectionStrategy} which ramps the weight of newly added
 * {@link Endpoint}s using {@link WeightTransition},
 * {@code rampingUpIntervalMillis} and {@code rampingUpTaskWindow}.
 * If more than one {@link Endpoint} are added within the {@code rampingUpTaskWindow}, the weights of
 * them are updated together. If there's already a scheduled job and new {@link Endpoint}s are added
 * within the {@code rampingUpTaskWindow}, they are updated together.
 * This is an example of how it works when {@code rampingUpTaskWindow} is 500 milliseconds and
 * {@code rampingUpIntervalMillis} is 2000 milliseconds:
 * <pre>{@code
 * ----------------------------------------------------------------------------------------------------
 *     A         B                             C                                       D
 *     t0        t1                            t2                                      t3         t4
 * ----------------------------------------------------------------------------------------------------
 *     0ms       t0 + 200ms                    t0 + 1000ms                          t0 + 1800ms  t0 + 2000ms
 * }</pre>
 * A and B are ramped up right away when they are added and they are ramped up together at t4.
 * C is updated alone every 2000 milliseconds. D is ramped up together with A and B at t4.
 */
final class WeightRampingUpStrategy
        implements EndpointSelectionStrategy,
                   LoadBalancerFactory<LoadBalancer<Endpoint, ClientRequestContext>> {

    static final EndpointSelectionStrategy INSTANCE = EndpointSelectionStrategy.builderForRampingUp()
                                                                               .build();

    private final WeightTransition<Endpoint> weightTransition;
    private final Supplier<EventExecutor> executorSupplier;
    private final long rampingUpIntervalMillis;
    private final int totalSteps;
    private final long rampingUpTaskWindowMillis;
    private final Ticker ticker;
    private final Function<Endpoint, Long> timestampFunction;

    WeightRampingUpStrategy(WeightTransition<Endpoint> weightTransition,
                            Supplier<EventExecutor> executorSupplier, long rampingUpIntervalMillis,
                            int totalSteps, long rampingUpTaskWindowMillis,
                            Function<Endpoint, Long> timestampFunction, Ticker ticker) {
        this.weightTransition = requireNonNull(weightTransition, "weightTransition");
        this.executorSupplier = requireNonNull(executorSupplier, "executorSupplier");
        checkArgument(rampingUpIntervalMillis > 0,
                      "rampingUpIntervalMillis: %s (expected: > 0)", rampingUpIntervalMillis);
        this.rampingUpIntervalMillis = rampingUpIntervalMillis;
        checkArgument(totalSteps > 0, "totalSteps: %s (expected: > 0)", totalSteps);
        this.totalSteps = totalSteps;
        checkArgument(rampingUpTaskWindowMillis >= 0,
                      "rampingUpTaskWindowMillis: %s (expected: > 0)",
                      rampingUpTaskWindowMillis);
        this.rampingUpTaskWindowMillis = rampingUpTaskWindowMillis;
        this.timestampFunction = timestampFunction;
        this.ticker = requireNonNull(ticker, "ticker");
    }

    @Override
    public EndpointSelector newSelector(EndpointGroup endpointGroup) {
        return new DefaultEndpointSelector<>(endpointGroup, this);
    }

    @Override
    public LoadBalancer<Endpoint, ClientRequestContext> newLoadBalancer(
            @Nullable LoadBalancer<Endpoint, ClientRequestContext> oldLoadBalancer, List<Endpoint> candidates) {
        if (oldLoadBalancer == null) {
            final UpdatableLoadBalancer<Endpoint> newLoadBalancer =
                    LoadBalancer.builderForRampingUp(candidates)
                                .rampingUpIntervalMillis(rampingUpIntervalMillis)
                                .rampingUpTaskWindowMillis(rampingUpTaskWindowMillis)
                                .totalSteps(totalSteps)
                                .weightTransition(weightTransition)
                                .timestampFunction(timestampFunction)
                                .executor(executorSupplier.get())
                                .ticker(ticker)
                                .build();
            return unsafeCast(newLoadBalancer);
        } else {
            assert oldLoadBalancer instanceof UpdatableLoadBalancer;
            @SuppressWarnings("unchecked")
            final UpdatableLoadBalancer<Endpoint> casted =
                    (UpdatableLoadBalancer<Endpoint>) (LoadBalancer<Endpoint, ?>) oldLoadBalancer;
            casted.updateCandidates(candidates);
            return unsafeCast(casted);
        }
    }
}
