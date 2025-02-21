/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.common.loadbalancer;

import static java.util.Objects.requireNonNull;

import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.SafeCloseable;

/**
 * A load balancer that selects an element from a list of candidates based on the given strategy.
 *
 * @param <T> the type of the candidate to be selected
 * @param <C> the type of the context used for selecting a candidate
 */
@SuppressWarnings("InterfaceMayBeAnnotatedFunctional")
@UnstableApi
public interface LoadBalancer<T, C> extends SafeCloseable {

    /**
     * Returns a {@link LoadBalancer} that selects a candidate using the round-robin strategy.
     */
    static <T> SimpleLoadBalancer<T> ofRoundRobin(Iterable<? extends T> candidates) {
        requireNonNull(candidates, "candidates");
        return new RoundRobinLoadBalancer<>(candidates);
    }

    /**
     * Returns a {@link LoadBalancer} that selects a candidate using the weighted round-robin strategy that
     * implements <a href="https://en.wikipedia.org/wiki/Weighted_round_robin#Interleaved_WRR">Interleaved WRR</a>
     * algorithm.
     *
     * @param weightFunction the weight function which returns the weight of the candidate.
     */
    static <T> SimpleLoadBalancer<T> ofWeightedRoundRobin(Iterable<? extends T> candidates,
                                                          ToIntFunction<? super T> weightFunction) {
        requireNonNull(candidates, "candidates");
        requireNonNull(weightFunction, "weightFunction");
        //noinspection unchecked
        return new WeightedRoundRobinLoadBalancer<>((Iterable<T>) candidates,
                                                    (ToIntFunction<T>) weightFunction);
    }

    /**
     * Returns a {@link LoadBalancer} that selects a candidate using the weighted round-robin strategy that
     * implements <a href="https://en.wikipedia.org/wiki/Weighted_round_robin#Interleaved_WRR">Interleaved WRR</a>
     * algorithm.
     */
    static <T extends Weighted> SimpleLoadBalancer<T> ofWeightedRoundRobin(Iterable<? extends T> candidates) {
        requireNonNull(candidates, "candidates");
        //noinspection unchecked
        return new WeightedRoundRobinLoadBalancer<>((Iterable<T>) candidates, null);
    }

    /**
     * Returns a {@link LoadBalancer} that selects a candidate using the weighted round-robin strategy that
     * implements <a href="https://en.wikipedia.org/wiki/Weighted_round_robin#Interleaved_WRR">Interleaved WRR</a>
     * algorithm.
     */
    @SafeVarargs
    static <T extends Weighted> SimpleLoadBalancer<T> ofWeightedRoundRobin(T... candidates) {
        requireNonNull(candidates, "candidates");
        return ofWeightedRoundRobin(ImmutableList.copyOf(candidates));
    }

    /**
     * Returns a {@link LoadBalancer} that selects a candidate using the sticky strategy.
     * The {@link ToLongFunction} is used to compute hashes for consistent hashing.
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
     * LoadBalancer<Endpoint, ClientRequestContext> strategy = LoadBalancer.ofSticky(endpoints, hasher);
     * }</pre>
     */
    static <T, C> LoadBalancer<T, C> ofSticky(Iterable<? extends T> candidates,
                                              ToLongFunction<? super C> contextHasher) {
        requireNonNull(candidates, "candidates");
        requireNonNull(contextHasher, "contextHasher");
        return new StickyLoadBalancer<>(candidates, contextHasher);
    }

    /**
     * Returns a {@link LoadBalancer} that selects a candidate using the weighted random distribution strategy.
     *
     * @param weightFunction the weight function which returns the weight of the candidate.
     */
    static <T, C> SimpleLoadBalancer<T> ofWeightedRandom(Iterable<? extends T> candidates,
                                                         ToIntFunction<? super T> weightFunction) {
        requireNonNull(candidates, "candidates");
        requireNonNull(weightFunction, "weightFunction");
        return new WeightedRandomLoadBalancer<>(candidates, weightFunction);
    }

    /**
     * Returns a {@link LoadBalancer} that selects a candidate using the weighted random distribution strategy.
     */
    static <T extends Weighted> SimpleLoadBalancer<T> ofWeightedRandom(
            Iterable<? extends T> candidates) {
        requireNonNull(candidates, "candidates");
        return new WeightedRandomLoadBalancer<>(candidates, null);
    }

    /**
     * Returns a {@link LoadBalancer} that selects a candidate using the weighted random distribution strategy.
     */
    @SafeVarargs
    static <T extends Weighted> SimpleLoadBalancer<T> ofWeightedRandom(T... candidates) {
        requireNonNull(candidates, "candidates");
        return ofWeightedRandom(ImmutableList.copyOf(candidates));
    }

    /**
     * Returns a weight ramping up {@link LoadBalancer} which ramps the weight of newly added
     * candidates using {@link WeightTransition#linear()}. The candidate is selected
     * using weighted random distribution.
     * The weights of {@link Endpoint}s are ramped up by 10 percent every 2 seconds up to 100 percent
     * by default. If you want to customize the parameters,
     * use {@link #builderForRampingUp(Iterable, ToIntFunction)}.
     *
     * @param weightFunction the weight function which returns the weight of the candidate.
     */
    static <T> UpdatableLoadBalancer<T> ofRampingUp(Iterable<? extends T> candidates,
                                                    ToIntFunction<? super T> weightFunction) {
        requireNonNull(candidates, "candidates");
        requireNonNull(weightFunction, "weightFunction");
        return LoadBalancer.<T>builderForRampingUp(candidates, weightFunction)
                           .build();
    }

    /**
     * Returns a weight ramping up {@link LoadBalancer} which ramps the weight of newly added
     * candidates using {@link WeightTransition#linear()}. The candidate is selected
     * using weighted random distribution.
     * The weights of {@link Endpoint}s are ramped up by 10 percent every 2 seconds up to 100 percent
     * by default. If you want to customize the parameters, use {@link #builderForRampingUp(Iterable)}.
     */
    static <T extends Weighted> UpdatableLoadBalancer<T> ofRampingUp(Iterable<? extends T> candidates) {
        requireNonNull(candidates, "candidates");
        return LoadBalancer.<T>builderForRampingUp(candidates).build();
    }

    /**
     * Returns a weight ramping up {@link LoadBalancer} which ramps the weight of newly added
     * candidates using {@link WeightTransition#linear()}. The candidate is selected
     * using weighted random distribution.
     * The weights of {@link Endpoint}s are ramped up by 10 percent every 2 seconds up to 100 percent
     * by default. If you want to customize the parameters, use {@link #builderForRampingUp(Iterable)}.
     */
    @SafeVarargs
    static <T extends Weighted> UpdatableLoadBalancer<T> ofRampingUp(T... candidates) {
        requireNonNull(candidates, "candidates");
        return ofRampingUp(ImmutableList.copyOf(candidates));
    }

    /**
     * Returns a new {@link RampingUpLoadBalancerBuilder} that builds
     * a {@link LoadBalancer} which ramps up the weight of newly added
     * candidates. The candidate is selected using weighted random distribution.
     *
     * @param weightFunction the weight function which returns the weight of the candidate.
     */
    static <T> RampingUpLoadBalancerBuilder<T> builderForRampingUp(
            Iterable<? extends T> candidates, ToIntFunction<? super T> weightFunction) {
        requireNonNull(candidates, "candidates");
        requireNonNull(weightFunction, "weightFunction");
        //noinspection unchecked
        return new RampingUpLoadBalancerBuilder<>((Iterable<T>) candidates, (ToIntFunction<T>) weightFunction);
    }

    /**
     * Returns a new {@link RampingUpLoadBalancerBuilder} that builds
     * a {@link LoadBalancer} which ramps up the weight of newly added
     * candidates. The candidate is selected using weighted random distribution.
     */
    static <T extends Weighted> RampingUpLoadBalancerBuilder<T> builderForRampingUp(
            Iterable<? extends T> candidates) {
        requireNonNull(candidates, "candidates");
        //noinspection unchecked
        return new RampingUpLoadBalancerBuilder<>((Iterable<T>) candidates, null);
    }

    /**
     * Returns a new {@link RampingUpLoadBalancerBuilder} that builds
     * a {@link LoadBalancer} which ramps up the weight of newly added
     * candidates. The candidate is selected using weighted random distribution.
     */
    @SafeVarargs
    static <T extends Weighted> RampingUpLoadBalancerBuilder<T> builderForRampingUp(T... candidates) {
        requireNonNull(candidates, "candidates");
        return builderForRampingUp(ImmutableList.copyOf(candidates));
    }

    /**
     * Selects and returns an element from the list of candidates based on the strategy.
     * {@code null} is returned if no candidate is available.
     */
    @Nullable
    T pick(C context);

    @Override
    default void close() {}
}
