/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.armeria.internal.common.util;

import it.unimi.dsi.fastutil.Hash.Strategy;

public final class IdentityHashStrategy<T> implements Strategy<T> {

    @SuppressWarnings("rawtypes")
    private static final IdentityHashStrategy INSTANCE = new IdentityHashStrategy();

    @SuppressWarnings("unchecked")
    public static <T> IdentityHashStrategy<T> of() {
        return INSTANCE;
    }

    private IdentityHashStrategy() {}

    @Override
    public int hashCode(T o) {
        return System.identityHashCode(o);
    }

    @Override
    public boolean equals(T a, T b) {
        return a == b;
    }
}
