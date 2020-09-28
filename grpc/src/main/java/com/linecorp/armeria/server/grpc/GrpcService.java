/*
 * Copyright 2016 LINE Corporation
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
import java.util.Set;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.HttpServiceWithRoutes;
import com.linecorp.armeria.server.Route;

import io.grpc.MethodDescriptor;
import io.grpc.ServerMethodDefinition;
import io.grpc.ServerServiceDefinition;

/**
 * An {@link HttpService} that implements the gRPC wire protocol. Interfaces and binding logic of gRPC
 * generated stubs are supported, however compatibility with gRPC's core java API is best effort.
 *
 * <p>Unsupported features:
 * <ul>
 *     <li>
 *         There are some differences in the HTTP/2 error code returned from an Armeria server vs gRPC server
 *         when dealing with transport errors and deadlines. Generally, the client will see an UNKNOWN status
 *         when the official server may have returned CANCELED.
 *     </li>
 * </ul>
 */
public interface GrpcService extends HttpServiceWithRoutes {

    /**
     * Returns a new {@link GrpcServiceBuilder}.
     */
    static GrpcServiceBuilder builder() {
        return new GrpcServiceBuilder();
    }

    /**
     * Returns whether this service handles framed requests.
     *
     * @return {@code true} if handles framed requests, or {@code false} if handles unframed requests.
     */
    boolean isFramed();

    /**
     * Returns the {@link ServerServiceDefinition}s serviced by this service.
     */
    List<ServerServiceDefinition> services();

    /**
     * Returns a {@link Map} whose key is a route path and whose value is {@link MethodDescriptor}, which is
     * serviced by this service.
     */
    default Map<String, MethodDescriptor<?, ?>> methods() {
        return services().stream()
                         .flatMap(service -> service.getMethods().stream())
                         .map(ServerMethodDefinition::getMethodDescriptor)
                         .collect(toImmutableMap(MethodDescriptor::getFullMethodName, Function.identity()));
    }

    /**
     * Returns the {@link SerializationFormat}s supported by this service.
     */
    Set<SerializationFormat> supportedSerializationFormats();

    @Override
    default boolean shouldCachePath(String path, @Nullable String query, Route route) {
        // gRPC services always have a single path per method that is safe to cache.
        return true;
    }
}
