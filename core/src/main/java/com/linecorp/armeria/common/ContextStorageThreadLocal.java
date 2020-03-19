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

package com.linecorp.armeria.common;

import static java.util.Objects.requireNonNull;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.util.UnstableApi;

import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.internal.InternalThreadLocalMap;

/**
 * A {@link ContextStorage} that uses thread-local to store {@link RequestContext}.
 * Override this.
 */
@UnstableApi
public class ContextStorageThreadLocal implements ContextStorage {

    private static final FastThreadLocal<RequestContext> context = new FastThreadLocal<>();

    @Nullable
    @Override
    @SuppressWarnings("unchecked")
    public <T extends RequestContext> T push(RequestContext toPush) {
        requireNonNull(toPush, "toPush");
        final InternalThreadLocalMap map = InternalThreadLocalMap.get();
        final RequestContext oldCtx = context.get(map);
        context.set(map, toPush);
        return (T) oldCtx;
    }

    @Override
    public void pop(@Nullable RequestContext toRestore) {
        context.set(toRestore);
    }

    @Nullable
    @Override
    @SuppressWarnings("unchecked")
    public <T extends RequestContext> T currentOrNull() {
        return (T) context.get();
    }
}
