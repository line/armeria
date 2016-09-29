/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.tracing;

import static java.util.Objects.requireNonNull;

import javax.annotation.Nullable;

import com.github.kristofa.brave.Brave;
import com.github.kristofa.brave.ServerRequestAdapter;
import com.github.kristofa.brave.ServerRequestInterceptor;
import com.github.kristofa.brave.ServerResponseAdapter;
import com.github.kristofa.brave.ServerResponseInterceptor;
import com.github.kristofa.brave.ServerSpan;
import com.github.kristofa.brave.ServerSpanThreadBinder;
import com.github.kristofa.brave.ServerTracer;

class ServerTracingInterceptor {

    private final ServerTracer serverTracer;

    private final ServerRequestInterceptor requestInterceptor;

    private final ServerResponseInterceptor responseInterceptor;

    private final ServerSpanThreadBinder spanThreadBinder;

    ServerTracingInterceptor(Brave brave) {
        requireNonNull(brave, "brave");
        serverTracer = brave.serverTracer();
        requestInterceptor = brave.serverRequestInterceptor();
        responseInterceptor = brave.serverResponseInterceptor();
        spanThreadBinder = brave.serverSpanThreadBinder();
    }

    @Nullable
    ServerSpan openSpan(ServerRequestAdapter adapter) {
        requestInterceptor.handle(adapter);
        return spanThreadBinder.getCurrentServerSpan();
    }

    void setSpan(ServerSpan span) {
        spanThreadBinder.setCurrentSpan(span);
    }

    void closeSpan(ServerSpan span, ServerResponseAdapter adapter) {
        spanThreadBinder.setCurrentSpan(span);
        responseInterceptor.handle(adapter);
    }

    void clearSpan() {
        serverTracer.clearCurrentSpan();
    }

}
