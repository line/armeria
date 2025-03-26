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

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;

/**
 * A round robin {@link LoadBalancer}.
 *
 * <p>For example, with node a, b and c, then select result is abc abc ...
 */
final class RoundRobinLoadBalancer<T> implements SimpleLoadBalancer<T> {

    private final AtomicInteger sequence = new AtomicInteger();
    private final List<T> candidates;

    RoundRobinLoadBalancer(Iterable<? extends T> candidates) {
        this.candidates = ImmutableList.copyOf(candidates);
    }

    @Nullable
    @Override
    public T pick() {
        if (candidates.isEmpty()) {
            return null;
        }

        final int currentSequence = sequence.getAndIncrement();
        return candidates.get(Math.abs(currentSequence % candidates.size()));
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("candidates", candidates)
                          .toString();
    }
}
