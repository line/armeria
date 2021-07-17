/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.common.brave;

import static com.linecorp.armeria.internal.common.brave.TraceContextUtil.currentTraceContext;
import static com.linecorp.armeria.internal.common.brave.TraceContextUtil.traceContext;

import java.util.ArrayDeque;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RequestContextStorage;
import com.linecorp.armeria.common.RequestContextStorageHook;
import com.linecorp.armeria.common.RequestContextStorageWrapper;

import brave.propagation.CurrentTraceContext.Scope;
import brave.propagation.TraceContext;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.FastThreadLocal;
import io.netty.util.internal.InternalThreadLocalMap;

enum RequestContextCurrentTraceContextHook implements RequestContextStorageHook {

    INSTANCE;

    private static final AttributeKey<Scope> SCOPE_KEY =
            AttributeKey.valueOf(RequestContextCurrentTraceContextHook.class, "SCOPE_KEY");

    private final FastThreadLocal<ArrayDeque<Scope>> scopes = new FastThreadLocal<>();

    private static final Scope CONTEXT_SCOPE = new Scope() {
        @Override
        public void close() {}

        @Override
        public String toString() {
            return "RequestContextScope";
        }
    };

    @Override
    public RequestContextStorage apply(RequestContextStorage contextStorage) {
        return new RequestContextStorageWrapper(contextStorage) {

            @Nullable
            @Override
            public <T extends RequestContext> T push(RequestContext toPush) {
                final T oldCtx = super.push(toPush);
                final ThreadLocal<ArrayDeque> local =
                        ThreadLocal.withInitial(() -> new ArrayDeque());

                final RequestContextCurrentTraceContext currentTraceContext = currentTraceContext(toPush);
                if (currentTraceContext != null) {
                    final TraceContext traceContext = traceContext(toPush);
                    if (traceContext != null) {
                        // TODO(ikhoon): Provide a way to pass an attachment to
                        final InternalThreadLocalMap map = InternalThreadLocalMap.get();
                        ArrayDeque<Scope> stack = scopes.get(map);
                        if (stack == null) {
                            stack = new ArrayDeque<>();
                        }

                        final Scope scope = currentTraceContext.decorateScope(traceContext, CONTEXT_SCOPE);
                        stack.push(scope);
                        scopes.set(map, stack);
                    }
                }

                return oldCtx;
            }

            @Override
            public void pop(RequestContext current, @Nullable RequestContext toRestore) {
                final InternalThreadLocalMap map = InternalThreadLocalMap.get();
                final ArrayDeque<Scope> stack = scopes.get(map);
                if (stack != null && !stack.isEmpty()) {
                    final Scope scope = stack.pop();
                    scope.close();
                }
                super.pop(current, toRestore);
            }
        };
    }
}
