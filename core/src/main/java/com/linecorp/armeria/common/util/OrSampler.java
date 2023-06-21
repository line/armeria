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

import java.util.Random;

/**
 * This sampler is appropriate for low-traffic instrumentation (ex servers that each receive <100K
 * requests), or those who do not provision random trace ids. It is not appropriate for collectors
 * as the sampling decision isn't idempotent (consistent based on trace id).
 *
 * <h2>Implementation</h2>
 *
 * <p>This initializes a random bitset of size 100 (corresponding to 1% granularity). This means
 * that it is accurate in units of 100 traces. At runtime, this loops through the bitset, returning
 * the value according to a counter.
 *
 * <p>Forked from brave-core 5.6.3 at d4cbd86e1df75687339da6ec2964d42ab3a8cf14
 */
final class OrSampler<T> implements Sampler<T> {

    private final Sampler<T> left, right;

    OrSampler(Sampler<T> left, Sampler<T> right) {
        this.left = left;
        this.right = right;
    }

    @Override
    public boolean isSampled(T t) {
        return left.isSampled(t) || right.isSampled(t);
    }

    @Override
    public String toString() {
        return left.toString() + " or " + right.toString();
    }
}
