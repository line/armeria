/*
 * Copyright 2019 LINE Corporation
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

package com.linecorp.armeria.internal.brave;

import com.linecorp.armeria.common.RequestContext;

import brave.propagation.TraceContext;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;

public final class TraceContextUtil {

    private static final AttributeKey<TraceContext> TRACE_CONTEXT_KEY =
            AttributeKey.valueOf(TraceContextUtil.class, "TRACE_CONTEXT");

    /**
     * Use this to ensure the trace context propagates to children.
     *
     * <p>Ex.
     * <pre>{@code
     *  // Ensure the trace context propagates to children
     * ctx.onChild(RequestContextCurrentTraceContext::copy);
     * }</pre>
     */
    public static void copy(RequestContext src, RequestContext dst) {
        dst.attr(TRACE_CONTEXT_KEY).set(src.attr(TRACE_CONTEXT_KEY).get());
    }

    public static Attribute<TraceContext> getTraceContextAttribute(RequestContext ctx) {
        return ctx.attr(TRACE_CONTEXT_KEY);
    }

    private TraceContextUtil() {}
}
