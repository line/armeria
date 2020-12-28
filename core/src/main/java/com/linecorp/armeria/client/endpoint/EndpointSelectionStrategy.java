/*
 * Copyright 2016 LINE Corporation
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

import java.util.function.ToLongFunction;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.HttpRequest;

/**
 * {@link Endpoint} selection strategy that creates a {@link EndpointSelector}.
 */
@FunctionalInterface
public interface EndpointSelectionStrategy {

    /**
     * Returns a weighted round-robin strategy. The endpoint is selected using
     * <a href="https://en.wikipedia.org/wiki/Weighted_round_robin#Interleaved_WRR">Interleaved WRR</a>
     * algorithm.
     *
     * @see #roundRobin()
     * @see #sticky(ToLongFunction)
     */
    static EndpointSelectionStrategy weightedRoundRobin() {
        return WeightedRoundRobinStrategy.INSTANCE;
    }

    /**
     * Returns a round-robin strategy, which ignores {@link Endpoint#weight()}.
     *
     * @see #weightedRoundRobin()
     * @see #sticky(ToLongFunction)
     */
    static EndpointSelectionStrategy roundRobin() {
        return RoundRobinStrategy.INSTANCE;
    }

    /**
     * Returns a weight ramping up {@link EndpointSelectionStrategy} which ramps the weight of newly added
     * {@link Endpoint}s using {@link EndpointWeightTransition#linear()}. The {@link Endpoint} is selected
     * using weighted random distribution.
     * The weights of {@link Endpoint}s are ramped up by 10 percent every 2 seconds up to 100 percent
     * by default. If you want to customize the parameters, use {@link #builderForRampingUp()}.
     */
    static EndpointSelectionStrategy rampingUp() {
        return builderForRampingUp().build();
    }

    /**
     * Returns a new {@link WeightRampingUpStrategyBuilder} that builds
     * a weight ramping up {@link EndpointSelectionStrategy} which ramps the weight of newly added
     * {@link Endpoint}s. The {@link Endpoint} is selected using weighted random distribution.
     */
    static WeightRampingUpStrategyBuilder builderForRampingUp() {
        return new WeightRampingUpStrategyBuilder();
    }

    /**
     * Returns a sticky strategy which uses a user passed {@link ToLongFunction} to compute hashes for
     * consistent hashing.
     *
     * <p>This strategy can be useful when all requests that qualify some given criteria must be sent to
     * the same backend server. A common use case is to send all requests for the same logged-in user to
     * the same backend, which could have a local cache keyed by user id.
     *
     * <p>In below example, created strategy will route all {@link HttpRequest} which have the same value for
     * key "cookie" of its header to the same server:
     *
     * <pre>{@code
     * ToLongFunction<ClientRequestContext> hasher = (ClientRequestContext ctx) -> {
     *     return ((HttpRequest) ctx.request()).headers().get(HttpHeaderNames.COOKIE).hashCode();
     * };
     * EndpointSelectionStrategy strategy = EndpointSelectionStrategy.sticky(hasher);
     * }</pre>
     *
     * @see #roundRobin()
     * @see #weightedRoundRobin()
     */
    static EndpointSelectionStrategy sticky(ToLongFunction<? super ClientRequestContext> requestContextHasher) {
        return new StickyEndpointSelectionStrategy(requestContextHasher);
    }

    /**
     * Creates a new {@link EndpointSelector} that selects an {@link Endpoint} from the specified
     * {@link EndpointGroup}.
     */
    EndpointSelector newSelector(EndpointGroup endpointGroup);
}
