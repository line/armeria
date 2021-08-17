/*
 *  Copyright 2020 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.common;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.atomic.AtomicInteger;

import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.internal.InternalThreadLocalMap;

public final class CustomRequestContextStorageProvider implements RequestContextStorageProvider {

    private static final FastThreadLocal<RequestContext> context = new FastThreadLocal<>();

    private static final AtomicInteger pushCalled = new AtomicInteger();

    private static final AtomicInteger popCalled = new AtomicInteger();

    @Nullable
    static RequestContext current() {
        return context.get();
    }

    static int pushCalled() {
        return pushCalled.get();
    }

    static int popCalled() {
        return popCalled.get();
    }

    @Override
    public RequestContextStorage newStorage() {
        return new CustomRequestContextStorage();
    }

    static final class CustomRequestContextStorage implements RequestContextStorage {
        @Nullable
        @Override
        @SuppressWarnings("unchecked")
        public <T extends RequestContext> T push(RequestContext toPush) {
            requireNonNull(toPush, "toPush");
            pushCalled.incrementAndGet();
            final InternalThreadLocalMap map = InternalThreadLocalMap.get();
            final RequestContext oldCtx = context.get(map);
            context.set(map, toPush);
            return (T) oldCtx;
        }

        @Override
        public void pop(RequestContext current, @Nullable RequestContext toRestore) {
            popCalled.incrementAndGet();
            context.set(toRestore);
        }

        @Nullable
        @Override
        @SuppressWarnings("unchecked")
        public <T extends RequestContext> T currentOrNull() {
            return (T) context.get();
        }
    }
}
