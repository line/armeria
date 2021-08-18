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

package com.linecorp.armeria.server.grpc;

import com.linecorp.armeria.common.annotation.Nullable;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.InternalServer;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;

final class ProtoReflectionServiceInterceptor implements ServerInterceptor {

    @Nullable
    private Server server;

    @Override
    public <I, O> Listener<I> interceptCall(ServerCall<I, O> call, Metadata headers,
                                            ServerCallHandler<I, O> next) {
        // Should set server before calling this
        assert server != null;

        final Context context = Context.current().withValue(InternalServer.SERVER_CONTEXT_KEY, server);
        return Contexts.interceptCall(context, call, headers, next);
    }

    public void setServer(Server server) {
        this.server = server;
    }
}
