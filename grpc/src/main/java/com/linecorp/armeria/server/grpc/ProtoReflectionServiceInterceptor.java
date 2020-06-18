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

import static com.google.common.collect.ImmutableMap.toImmutableMap;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.server.ServiceRequestContext;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.InternalServer;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerServiceDefinition;

final class ProtoReflectionServiceInterceptor implements ServerInterceptor {

    static final ProtoReflectionServiceInterceptor INSTANCE = new ProtoReflectionServiceInterceptor();

    @Nullable
    private static Server dummyServer;

    private ProtoReflectionServiceInterceptor() {}

    @Override
    public <I, O> Listener<I> interceptCall(ServerCall<I, O> call, Metadata headers,
                                            ServerCallHandler<I, O> next) {
        if (dummyServer == null) {
            synchronized (INSTANCE) {
                dummyServer = newDummyServer();
            }
        }

        final Context context = Context.current().withValue(InternalServer.SERVER_CONTEXT_KEY, dummyServer);
        return Contexts.interceptCall(context, call, headers, next);
    }

    private static Server newDummyServer() {
        final Map<String, ServerServiceDefinition> grpcServices =
                ServiceRequestContext.current().config().server().config().virtualHosts().stream()
                                     .flatMap(host -> host.serviceConfigs().stream())
                                     .map(serviceConfig -> serviceConfig.service().as(FramedGrpcService.class))
                                     .filter(Objects::nonNull)
                                     .flatMap(service -> service.services().stream())
                                     // Armeria allows the same service to be registered multiple times at
                                     // different paths, but proto reflection service only supports a single
                                     // instance of each service so we dedupe here.
                                     .collect(toImmutableMap(def -> def.getServiceDescriptor().getName(),
                                                             Function.identity(),
                                                             (a, b) -> a));

        return new Server() {
            @Override
            public Server start() {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<ServerServiceDefinition> getServices() {
                return ImmutableList.copyOf(grpcServices.values());
            }

            @Override
            public List<ServerServiceDefinition> getImmutableServices() {
                // NB: This will probably go away in favor of just getServices above, so we
                // implement both the same.
                // https://github.com/grpc/grpc-java/issues/4600
                return getServices();
            }

            @Override
            public List<ServerServiceDefinition> getMutableServices() {
                // Armeria does not have the concept of mutable services.
                return ImmutableList.of();
            }

            @Override
            public Server shutdown() {
                throw new UnsupportedOperationException();
            }

            @Override
            public Server shutdownNow() {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean isShutdown() {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean isTerminated() {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void awaitTermination() {
                throw new UnsupportedOperationException();
            }
        };
    }
}
