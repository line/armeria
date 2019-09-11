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
 *
 * <p>Forked from brave-core 5.6.3 at 3be55b5cccf881104bdd80c93e97d2575b83952d
 */
@FunctionalInterface
public interface Sampler<T> {
    /**
     * Returns a sampler, given a rate expressed as a floating point number between {@code 0.0} and {@code 1.0}.
     *
     * @param rate minimum sampling rate between {@code 0.01} and {@code 1.0}.
     */
    static <T> Sampler<T> random(double rate) {
        @SuppressWarnings("unchecked")
        final Sampler<T> cast = CountingSampler.create(rate);
        return cast;
    }

    /**
     * Returns a sampler, given a rate-limited on a per-second interval.
     *
     * @param samplesPerSecond an integer between {@code 0} and {@value Integer#MAX_VALUE}
     */
    static <T> Sampler<T> rateLimited(int samplesPerSecond) {
        @SuppressWarnings("unchecked")
        final Sampler<T> cast = RateLimitingSampler.create(samplesPerSecond);
        return cast;
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
     * Returns {@code true} if a request should be recorded.
     *
     * @param object The object to be decided on, can be ignored
     */
    boolean isSampled(T object);
}
