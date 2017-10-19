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

import static com.google.common.base.Preconditions.checkArgument;

/**
 * Sampler is responsible for deciding if a particular trace should be "sampled", i.e. whether the
 * overhead of tracing will occur and/or if a trace will be reported to the collection tier.
 *
 * <p>Zipkin v1 uses before-the-fact sampling. This means that the decision to keep or drop the
 * trace is made before any work is measured, or annotations are added. As such, the input parameter
 * to zipkin v1 samplers is the trace ID (64-bit random number).
 *
 * <p>The instrumentation sampling decision happens once, at the root of the trace, and is
 * propagated downstream. For this reason, the decision needn't be consistent based on trace ID.
 *
 * <p>Forked from brave-core.
 */
// abstract for factory-method support on Java language level 7
public abstract class Sampler {

    static final Sampler ALWAYS_SAMPLE = new Sampler() {
        @Override
        public boolean isSampled() {
            return true;
        }

        @Override
        public String toString() {
            return "AlwaysSample";
        }
    };

    static final Sampler NEVER_SAMPLE = new Sampler() {
        @Override
        public boolean isSampled() {
            return false;
        }

        @Override
        public String toString() {
            return "NeverSample";
        }
    };

    /**
     *  Returns true if a log should be recorded.
     */
    public abstract boolean isSampled();

    /**
     * Returns a sampler, given a rate expressed as a percentage.
     *
     * <p>The sampler returned is good for low volumes of traffic (<100K requests), as it is precise.
     * If you have high volumes of traffic, consider {@code BoundarySampler}.
     *
     * @param rate 0 for no sampling, 1 for full sampling, or in [0.01, 1] for random sampling.
     */
    public static Sampler create(float rate) {
        if (rate == 1.0) {
            return ALWAYS_SAMPLE;
        } else if (rate == 0.0) {
            return NEVER_SAMPLE;
        }
        checkArgument(rate >= 0.01f && rate < 1, "rate should be between 0.01 and 1: was %s", rate);
        return new CountingSampler(rate);
    }

    /**
     * Returns a {@link Sampler} for full sampling.
     */
    public static Sampler always() {
        return ALWAYS_SAMPLE;
    }
}
