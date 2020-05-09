/*
 * Copyright 2017 LINE Corporation
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
 *
 * Copyright 2013 <kristofa@github.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.linecorp.armeria.common.util;

/**
 * Sampler is responsible for deciding if a particular trace should be "sampled", i.e. whether the
 * overhead of tracing will occur and/or if a trace will be reported to the collection tier.
 *
 * <p>Zipkin v1 uses before-the-fact sampling. This means that the decision to keep or drop the
 * trace is made before any work is measured, or annotations are added. As such, the input parameter
 * to zipkin v1 samplers is the trace ID (lower 64-bits under the assumption all bits are random).
 *
 * <p>The instrumentation sampling decision happens once, at the root of the trace, and is
 * propagated downstream. For this reason, the algorithm needn't be consistent based on trace ID.
 */
@FunctionalInterface
public interface Sampler<T> {
    /**
     * Returns a probabilistic sampler which samples at the specified {@code probability}
     * between {@code 0.0} and {@code 1.0}.
     *
     * @param probability the probability expressed as a floating point number
     *                    between {@code 0.0} and {@code 1.0}.
     */
    static <T> Sampler<T> random(float probability) {
        return CountingSampler.create(probability);
    }

    /**
     * Returns a rate-limiting sampler which rate-limits up to the specified {@code samplesPerSecond}.
     *
     * @param samplesPerSecond an integer between {@code 0} and {@value Integer#MAX_VALUE}
     */
    static <T> Sampler<T> rateLimiting(int samplesPerSecond) {
        return RateLimitingSampler.create(samplesPerSecond);
    }

    /**
     * Returns a sampler that will always return {@code true}.
     */
    static <T> Sampler<T> always() {
        @SuppressWarnings("unchecked")
        final Sampler<T> cast = Samplers.ALWAYS;
        return cast;
    }

    /**
     * Returns a sampler that will always return {@code false}.
     */
    static <T> Sampler<T> never() {
        @SuppressWarnings("unchecked")
        final Sampler<T> cast = Samplers.NEVER;
        return cast;
    }

    /**
     * Returns a {@link Sampler} that is configured as specified in the given {@code specification} string.
     * The {@code specification} string must be formatted in one of the following formats:
     * <ul>
     *   <li>{@code "always"}
     *     <ul>
     *       <li>Returns the {@link Sampler} that always samples.</li>
     *     </ul>
     *   </li>
     *   <li>{@code "never"}
     *     <ul>
     *       <li>Returns the {@link Sampler} that never samples.</li>
     *     </ul>
     *   </li>
     *   <li>{@code "random=<probability>"} where {@code probability} is a floating point number
     *     between 0.0 and 1.0
     *     <ul>
     *       <li>Returns a probabilistic {@link Sampler} which samples at the specified probability.</li>
     *       <li>e.g. {@code "random=0.05"} to sample at 5% probability</li>
     *     </ul>
     *   </li>
     *   <li>{@code "rate-limit=<samples_per_sec>"} where {@code samples_per_sec} is a non-negative integer
     *     <ul>
     *       <li>Returns a rate-limiting {@link Sampler} which rate-limits up to the specified
     *           samples per second.</li>
     *       <li>e.g. {@code "rate-limit=10"} to sample 10 samples per second at most</li>
     *     </ul>
     *   </li>
     * </ul>
     */
    static <T> Sampler<T> of(String specification) {
        return Samplers.of(specification);
    }

    /**
     * Returns {@code true} if a request should be recorded.
     *
     * @param object the object to be decided on, can be ignored
     */
    boolean isSampled(T object);
}
