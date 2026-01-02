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
import java.util.function.ToLongFunction;

import org.jspecify.annotations.Nullable;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.Hashing;

final class StickyLoadBalancer<T, C> implements LoadBalancer<T, C> {

    private final ToLongFunction<? super C> contextHasher;
    private final List<T> candidates;

    StickyLoadBalancer(Iterable<? extends T> candidates,
                       ToLongFunction<? super C> contextHasher) {
        this.candidates = ImmutableList.copyOf(candidates);
        this.contextHasher = contextHasher;
    }

    @Nullable
    @Override
    public T pick(C context) {
        if (candidates.isEmpty()) {
            return null;
        }

        final long key = contextHasher.applyAsLong(context);
        final int nearest = Hashing.consistentHash(key, candidates.size());
        return candidates.get(nearest);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("contextHasher", contextHasher)
                          .add("candidates", candidates)
                          .toString();
    }
}
