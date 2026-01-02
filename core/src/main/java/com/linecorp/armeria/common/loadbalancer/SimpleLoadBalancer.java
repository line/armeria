/*
 * Copyright 2025 LINE Corporation
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

import org.jspecify.annotations.Nullable;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A simple {@link LoadBalancer} which does not require any parameter to pick a candidate.
 */
@SuppressWarnings("InterfaceMayBeAnnotatedFunctional")
@UnstableApi
public interface SimpleLoadBalancer<T> extends LoadBalancer<T, Object> {

    /**
     * {@inheritDoc} This method is equivalent to {@link #pick()}.
     *
     * @deprecated Use {@link #pick()} instead.
     */
    @Override
    @Nullable
    @Deprecated
    default T pick(Object unused) {
        return pick();
    }

    /**
     * Selects and returns an element from the list of candidates based on the strategy.
     * {@code null} is returned if no candidate is available.
     */
    @Nullable
    T pick();
}
