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

import java.util.HashMap;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableMap;

import io.grpc.ServerMethodDefinition;
import io.grpc.ServerServiceDefinition;

// Copied as is from grpc.
final class InternalHandlerRegistry {
    private final ImmutableMap<String, ServerMethodDefinition<?, ?>> methods;

    private InternalHandlerRegistry(ImmutableMap<String, ServerMethodDefinition<?, ?>> methods) {
        this.methods = methods;
    }

    @Nullable
    ServerMethodDefinition<?, ?> lookupMethod(String methodName) {
        return methods.get(methodName);
    }

    static class Builder {
        // Store per-service first, to make sure services are added/replaced atomically.
        private final HashMap<String, ServerServiceDefinition> services =
                new HashMap<String, ServerServiceDefinition>();

        Builder addService(ServerServiceDefinition service) {
            services.put(service.getServiceDescriptor().getName(), service);
            return this;
        }

        InternalHandlerRegistry build() {
            ImmutableMap.Builder<String, ServerMethodDefinition<?, ?>> mapBuilder =
                    ImmutableMap.builder();
            for (ServerServiceDefinition service : services.values()) {
                for (ServerMethodDefinition<?, ?> method : service.getMethods()) {
                    mapBuilder.put(method.getMethodDescriptor().getFullMethodName(), method);
                }
            }
            return new InternalHandlerRegistry(mapBuilder.build());
        }
    }
}
