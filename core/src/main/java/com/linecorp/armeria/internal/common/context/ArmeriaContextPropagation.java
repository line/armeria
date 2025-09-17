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

package com.linecorp.armeria.internal.common.context;

import java.util.ServiceLoader;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ContextSnapshotFactory;

public final class ArmeriaContextPropagation {

    private static final ContextRegistry REGISTRY = new ContextRegistry();

    static {
        // A separate registry is used to avoid pushing the same context twice when using
        // a different framework (e.g. reactor) with armeria
        final ServiceLoader<ThreadLocalAccessorProvider> loader =
                ServiceLoader.load(ThreadLocalAccessorProvider.class);
        loader.forEach(provider -> REGISTRY.registerThreadLocalAccessor(provider.threadLocalAccessor()));
    }

    private static final ContextSnapshotFactory globalFactory = ContextSnapshotFactory
            .builder()
            .contextRegistry(REGISTRY)
            .build();

    public static ContextSnapshot captureAll() {
        return globalFactory.captureAll();
    }

    private ArmeriaContextPropagation() {}
}
