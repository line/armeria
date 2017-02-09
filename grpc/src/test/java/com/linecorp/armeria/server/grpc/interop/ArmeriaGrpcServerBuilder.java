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



package com.linecorp.armeria.server.grpc.interop;

import java.io.File;
import java.util.concurrent.Executor;

import javax.annotation.Nullable;
import javax.net.ssl.SSLException;

import com.linecorp.armeria.common.http.HttpSessionProtocols;
import com.linecorp.armeria.server.grpc.GrpcServiceBuilder;

import io.grpc.BindableService;
import io.grpc.CompressorRegistry;
import io.grpc.DecompressorRegistry;
import io.grpc.HandlerRegistry;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerServiceDefinition;

public class ArmeriaGrpcServerBuilder extends ServerBuilder<ArmeriaGrpcServerBuilder> {

    private final com.linecorp.armeria.server.ServerBuilder armeriaServerBuilder;
    private final GrpcServiceBuilder grpcServiceBuilder;

    public ArmeriaGrpcServerBuilder(com.linecorp.armeria.server.ServerBuilder armeriaServerBuilder,
                                    GrpcServiceBuilder grpcServiceBuilder) {
        this.armeriaServerBuilder = armeriaServerBuilder;
        this.grpcServiceBuilder = grpcServiceBuilder;
    }

    @Override
    public ArmeriaGrpcServerBuilder directExecutor() {
        return this;
    }

    @Override
    public ArmeriaGrpcServerBuilder executor(@Nullable Executor executor) {
        return this;
    }

    @Override
    public ArmeriaGrpcServerBuilder addService(ServerServiceDefinition serverServiceDefinition) {
        grpcServiceBuilder.addService(serverServiceDefinition);
        return this;
    }

    @Override
    public ArmeriaGrpcServerBuilder addService(BindableService bindableService) {
        grpcServiceBuilder.addService(bindableService);
        return this;
    }

    @Override
    public ArmeriaGrpcServerBuilder fallbackHandlerRegistry(@Nullable HandlerRegistry handlerRegistry) {
        // Not supported
        return this;
    }

    @Override
    public ArmeriaGrpcServerBuilder useTransportSecurity(File certChain, File privateKey) {
        try {
            armeriaServerBuilder.sslContext(HttpSessionProtocols.HTTPS, certChain, privateKey);
        } catch (SSLException e) {
            throw new IllegalArgumentException(e);
        }
        return this;
    }

    @Override
    public ArmeriaGrpcServerBuilder decompressorRegistry(@Nullable DecompressorRegistry decompressorRegistry) {
        grpcServiceBuilder.decompressorRegistry(decompressorRegistry);
        return this;
    }

    @Override
    public ArmeriaGrpcServerBuilder compressorRegistry(@Nullable CompressorRegistry compressorRegistry) {
        grpcServiceBuilder.compressorRegistry(compressorRegistry);
        return this;
    }

    @Override
    public Server build() {
        armeriaServerBuilder.serviceUnder("/", grpcServiceBuilder.build());
        return new ArmeriaGrpcServer(armeriaServerBuilder.build());
    }
}
