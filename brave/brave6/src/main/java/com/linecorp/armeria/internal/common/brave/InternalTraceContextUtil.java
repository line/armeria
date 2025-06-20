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

package com.linecorp.armeria.internal.common.brave;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.brave.RequestContextCurrentTraceContext;

import brave.propagation.TraceContext;

/**
 * Internal class for manipulate the internal {@link ThreadLocal} for {@link RequestContextCurrentTraceContext}.
 * This class is reserved for internal usage and is subject to behavior change any time.
 */
public final class InternalTraceContextUtil {

    // Thread-local for storing TraceContext when invoking callbacks off the request thread.
    private static final ThreadLocal<TraceContext> THREAD_LOCAL_CONTEXT = new ThreadLocal<>();

    public static void set(@Nullable TraceContext traceContext) {
        THREAD_LOCAL_CONTEXT.set(traceContext);
    }

    @Nullable
    public static TraceContext get() {
        return THREAD_LOCAL_CONTEXT.get();
    }

    private InternalTraceContextUtil() {}
}
