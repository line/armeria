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

import javax.annotation.Nullable;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RequestContextStorage;
import com.linecorp.armeria.common.RequestContextStorageHook;
import com.linecorp.armeria.common.RequestContextStorageWrapper;

import brave.propagation.CurrentTraceContext.Scope;
import brave.propagation.TraceContext;
import io.netty.util.AttributeKey;

enum RequestContextCurrentTraceContextHook implements RequestContextStorageHook {

    INSTANCE;

    private static final AttributeKey<Scope> SCOPE_KEY =
            AttributeKey.valueOf(RequestContextCurrentTraceContextHook.class, "SCOPE_KEY");

    private static final Scope REQUEST_CONTEXT_SCOPE = new Scope() {
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
                final RequestContextCurrentTraceContext currentTraceContext = currentTraceContext(toPush);
                if (currentTraceContext != null) {
                    final TraceContext traceContext = traceContext(toPush);
                    if (traceContext != null) {
                        final Scope scope =
                                currentTraceContext.decorateScope(traceContext, REQUEST_CONTEXT_SCOPE);
                        toPush.setAttr(SCOPE_KEY, scope);
                    }
                }
                return super.push(toPush);
            }

            @Override
            public void pop(RequestContext current, @Nullable RequestContext toRestore) {
                final Scope scope = current.setAttr(SCOPE_KEY, null);
                if (scope != null) {
                    scope.close();
                }
                super.pop(current, toRestore);
            }
        };
    }
}
