/*
 * Copyright 2016 LINE Corporation
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

import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.internal.InternalThreadLocalMap;

final class RequestContextThreadLocal {

    private static final FastThreadLocal<RequestContext> context = new FastThreadLocal<>();

    @SuppressWarnings("unchecked")
    static <T extends RequestContext> T get() {
        return (T) context.get();
    }

    @SuppressWarnings("unchecked")
    static <T extends RequestContext> T getAndSet(RequestContext ctx) {
        final InternalThreadLocalMap map = InternalThreadLocalMap.get();
        final RequestContext oldCtx = context.get(map);
        context.set(map, ctx);
        return (T) oldCtx;
    }

    static void set(RequestContext ctx) {
        context.set(ctx);
    }

    static void remove() {
        context.remove();
    }

    private RequestContextThreadLocal() {}
}
