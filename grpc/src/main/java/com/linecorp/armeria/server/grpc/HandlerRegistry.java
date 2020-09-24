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
/*
 * Copyright 2016, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *    * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *
 *    * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.linecorp.armeria.server.grpc;

import static java.util.Objects.requireNonNull;

import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableMap;

import io.grpc.ServerMethodDefinition;
import io.grpc.ServerServiceDefinition;

/**
 * A registry of the implementation methods bound to a {@link GrpcService}. Used for method dispatch and
 * documentation generation.
 */
final class HandlerRegistry {
    private final Map<String, ServerServiceDefinition> services;
    private final Map<String, ServerMethodDefinition<?, ?>> methods;

    private HandlerRegistry(Map<String, ServerServiceDefinition> services,
                            Map<String, ServerMethodDefinition<?, ?>> methods) {
        this.services = requireNonNull(services, "services");
        this.methods = requireNonNull(methods, "methods");
    }

    @Nullable
    ServerMethodDefinition<?, ?> lookupMethod(String methodName) {
        return methods.get(methodName);
    }

    Map<String, ServerServiceDefinition> services() {
        return services;
    }

    Map<String, ServerMethodDefinition<?, ?>> methods() {
        return methods;
    }

    static class Builder {
        // Store per-service first, to make sure services are added/replaced atomically.
        private final Map<String, ServerServiceDefinition> services = new HashMap<>();

        Builder addService(ServerServiceDefinition service) {
            services.put(service.getServiceDescriptor().getName(), service);
            return this;
        }

        Builder addService(String path, ServerServiceDefinition service) {
            services.put(normalizePath(path), service);
            return this;
        }

        private static String extractMethodName(String fullMethodName) {
            final int methodIndex = fullMethodName.lastIndexOf('/');
            return fullMethodName.substring(methodIndex + 1);
        }

        private static String normalizePath(String path) {
            if (path.isEmpty()) {
                return path;
            }

            if (path.charAt(0) == '/') {
                path = path.substring(1);
            }

            if (path.isEmpty()) {
                return path;
            }

            final int lastCharIndex = path.length() - 1;
            if (path.charAt(lastCharIndex) == '/') {
                return path.substring(0, lastCharIndex);
            } else {
                return path;
            }
        }

        HandlerRegistry build() {
            final ImmutableMap.Builder<String, ServerMethodDefinition<?, ?>> mapBuilder =
                    ImmutableMap.builder();
            services.forEach((path, service) -> {
                for (ServerMethodDefinition<?, ?> method : service.getMethods()) {
                    final String fullMethodName = method.getMethodDescriptor().getFullMethodName();
                    mapBuilder.put(path + '/' + extractMethodName(fullMethodName), method);
                }
            });
            return new HandlerRegistry(ImmutableMap.copyOf(services), mapBuilder.build());
        }
    }
}
