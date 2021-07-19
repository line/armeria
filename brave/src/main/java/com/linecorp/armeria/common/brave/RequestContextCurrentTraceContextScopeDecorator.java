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

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RequestContextStorageListener;
import com.linecorp.armeria.common.util.SafeCloseable;

import brave.propagation.CurrentTraceContext.Scope;
import brave.propagation.TraceContext;

enum RequestContextCurrentTraceContextScopeDecorator implements RequestContextStorageListener {

    INSTANCE;

    private static final SafeCloseable NOOP_CLOSEABLE = () -> {};

    private static final Scope CONTEXT_SCOPE = new Scope() {
        @Override
        public void close() {}

        @Override
        public String toString() {
            return "RequestContextScope";
        }
    };

    @Override
    public SafeCloseable onPush(RequestContext context) {
        final RequestContextCurrentTraceContext currentTraceContext = currentTraceContext(context);
        if (currentTraceContext != null) {
            final TraceContext traceContext = traceContext(context);
            if (traceContext != null) {
                return currentTraceContext.decorateScope(traceContext, CONTEXT_SCOPE)::close;
            }
        }
        return NOOP_CLOSEABLE;
    }
}
