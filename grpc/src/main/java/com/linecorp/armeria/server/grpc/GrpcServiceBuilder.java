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

package com.linecorp.armeria.server.grpc;

import static com.google.common.base.MoreObjects.firstNonNull;
import static java.util.Objects.requireNonNull;

import javax.annotation.Nullable;

import com.linecorp.armeria.server.Service;

import io.grpc.BindableService;
import io.grpc.CompressorRegistry;
import io.grpc.DecompressorRegistry;
import io.grpc.ServerServiceDefinition;

/**
 * Constructs a {@link GrpcService} to serve GRPC services from within Armeria.
 */
public final class GrpcServiceBuilder {

    private final InternalHandlerRegistry.Builder registryBuilder =
            new InternalHandlerRegistry.Builder();

    @Nullable
    private DecompressorRegistry decompressorRegistry;

    @Nullable
    private CompressorRegistry compressorRegistry;

    /**
     * Adds a GRPC {@link ServerServiceDefinition} to this {@link GrpcServiceBuilder}, such as
     * what's returned by {@link BindableService#bindService()}.
     */
    public GrpcServiceBuilder addService(ServerServiceDefinition service) {
        registryBuilder.addService(requireNonNull(service, "service"));
        return this;
    }

    /**
     * Adds a GRPC {@link BindableService} to this {@link GrpcServiceBuilder}. Most GRPC service
     * implementations are {@link BindableService}s.
     */
    public GrpcServiceBuilder addService(BindableService bindableService) {
        return addService(bindableService.bindService());
    }

    /**
     * Sets the {@link DecompressorRegistry} to use when decompressing messages. If not set, will use
     * the default, which supports gzip only.
     */
    public GrpcServiceBuilder decompressorRegistry(DecompressorRegistry registry) {
        decompressorRegistry = requireNonNull(registry, "registry");
        return this;
    }

    /**
     * Sets the {@link CompressorRegistry} to use when compressing messages. If not set, will use the
     * default, which supports gzip only.
     */
    public GrpcServiceBuilder compressorRegistry(CompressorRegistry registry) {
        compressorRegistry = requireNonNull(registry, "registry");
        return this;
    }

    /**
     * Constructs a new {@link GrpcService} that can be bound to
     * {@link com.linecorp.armeria.server.ServerBuilder}. As GRPC services themselves are mounted at a path that
     * corresponds to their protobuf package, you will almost always want to bind to a prefix, e.g. by using
     * {@link com.linecorp.armeria.server.ServerBuilder#serviceUnder(String, Service)}.
     */
    public GrpcService build() {
        return new GrpcService(registryBuilder.build(),
                               firstNonNull(decompressorRegistry, DecompressorRegistry.getDefaultInstance()),
                               firstNonNull(compressorRegistry, CompressorRegistry.getDefaultInstance()));
    }
}
