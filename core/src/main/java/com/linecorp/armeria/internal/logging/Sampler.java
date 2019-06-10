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
package com.linecorp.armeria.internal.logging;

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
// abstract for factory-method support on Java language level 7
public abstract class Sampler {

    public static final Sampler ALWAYS_SAMPLE = new Sampler() {
        @Override
        public boolean isSampled() {
            return true;
        }

        @Override public String toString() {
            return "AlwaysSample";
        }
    };

    public static final Sampler NEVER_SAMPLE = new Sampler() {
        @Override
        public boolean isSampled() {
            return false;
        }

        @Override public String toString() {
            return "NeverSample";
        }
    };

    /**
     *  Returns true if a request should be recorded.
     */
    public abstract boolean isSampled();

    /**
     * Returns a sampler, given a rate expressed as a percentage.
     *
     * <p>The sampler returned is good for low volumes of traffic (<100K requests), as it is precise.
     * If you have high volumes of traffic, consider {@code BoundarySampler}.
     *
     * @param rate minimum sample rate is 0.01, or 1% of traces
     */
    public static Sampler create(float rate) {
        return CountingSampler.create(rate);
    }
}
