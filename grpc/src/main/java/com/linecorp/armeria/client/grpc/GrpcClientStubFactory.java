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

package com.linecorp.armeria.client.grpc;

import com.linecorp.armeria.common.annotation.Nullable;

import io.grpc.Channel;
import io.grpc.ServiceDescriptor;

/**
 * A factory that creates a gRPC client stub.
 */
public interface GrpcClientStubFactory {

    /**
     * Returns a {@link ServiceDescriptor} for the {@code clientType}.
     * {@code null} if the given {@code clientType} is unsupported.
     */
    @Nullable
    ServiceDescriptor findServiceDescriptor(Class<?> clientType);

    /**
     * Returns a gRPC client stub from the specified {@code clientType} and {@link Channel}.
     */
    Object newClientStub(Class<?> clientType, Channel channel);
}
