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

import static com.linecorp.armeria.internal.server.grpc.GrpcMethodUtil.extractMethodName;
import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.Route;

import io.grpc.MethodDescriptor;
import io.grpc.ServerMethodDefinition;
import io.grpc.ServerServiceDefinition;

/**
 * A registry of the implementation methods bound to a {@link GrpcService}. Used for method dispatch and
 * documentation generation.
 */
final class HandlerRegistry {
    private final ImmutableList<ServerServiceDefinition> services;
    private final ImmutableMap<String, ServerMethodDefinition<?, ?>> methods;
    private final ImmutableMap<Route, ServerMethodDefinition<?, ?>> methodsByRoute;
    private final ImmutableMap<MethodDescriptor<?, ?>, String> simpleMethodNames;

    private HandlerRegistry(ImmutableList<ServerServiceDefinition> services,
                            ImmutableMap<String, ServerMethodDefinition<?, ?>> methods,
                            ImmutableMap<Route, ServerMethodDefinition<?, ?>> methodsByRoute,
                            ImmutableMap<MethodDescriptor<?, ?>, String> simpleMethodNames) {
        this.services = requireNonNull(services, "services");
        this.methods = requireNonNull(methods, "methods");
        this.methodsByRoute = requireNonNull(methodsByRoute, "methodsByRoute");
        this.simpleMethodNames = requireNonNull(simpleMethodNames, "simpleMethodNames");
    }

    @Nullable
    ServerMethodDefinition<?, ?> lookupMethod(String methodName) {
        return methods.get(methodName);
    }

    @Nullable
    ServerMethodDefinition<?, ?> lookupMethod(Route route) {
        return methodsByRoute.get(route);
    }

    String simpleMethodName(MethodDescriptor<?, ?> methodName) {
        return simpleMethodNames.get(methodName);
    }

    List<ServerServiceDefinition> services() {
        return services;
    }

    Map<String, ServerMethodDefinition<?, ?>> methods() {
        return methods;
    }

    Map<Route, ServerMethodDefinition<?, ?>> methodsByRoute() {
        return methodsByRoute;
    }

    static final class Builder {
        private final List<Entry> entries = new ArrayList<>();

        Builder addService(ServerServiceDefinition service) {
            entries.add(new Entry(service.getServiceDescriptor().getName(), service, null));
            return this;
        }

        Builder addService(String path, ServerServiceDefinition service,
                           @Nullable MethodDescriptor<?, ?> methodDescriptor) {
            entries.add(new Entry(normalizePath(path, methodDescriptor == null), service, methodDescriptor));
            return this;
        }

        private static String normalizePath(String path, boolean isServicePath) {
            if (path.isEmpty()) {
                return path;
            }

            if (path.charAt(0) == '/') {
                path = path.substring(1);
            }
            if (path.isEmpty()) {
                return path;
            }

            if (isServicePath) {
                final int lastCharIndex = path.length() - 1;
                if (path.charAt(lastCharIndex) == '/') {
                    path = path.substring(0, lastCharIndex);
                }
            }

            return path;
        }

        List<Entry> entries() {
            return entries;
        }

        HandlerRegistry build() {
            // Store per-service first, to make sure services are added/replaced atomically.
            final Map<String, ServerServiceDefinition> services = new HashMap<>();
            final Map<String, ServerMethodDefinition<?, ?>> methods = new HashMap<>();
            final Map<Route, ServerMethodDefinition<?, ?>> methodsByRoute = new HashMap<>();
            final Map<MethodDescriptor<?, ?>, String> simpleMethodNames = new HashMap<>();

            for (Entry entry : entries) {
                final ServerServiceDefinition service = entry.service();
                final String path = entry.path();
                services.put(path, service);
                final MethodDescriptor<?, ?> methodDescriptor = entry.method();
                if (methodDescriptor == null) {
                    for (ServerMethodDefinition<?, ?> method : service.getMethods()) {
                        final MethodDescriptor<?, ?> methodDescriptor0 = method.getMethodDescriptor();
                        final String fullMethodName = methodDescriptor0.getFullMethodName();
                        final String simpleMethodName = extractMethodName(fullMethodName);
                        final String pathWithMethod = path + '/' + simpleMethodName;
                        methods.put(pathWithMethod, method);
                        methodsByRoute.put(Route.builder().exact('/' + pathWithMethod).build(), method);
                        simpleMethodNames.put(methodDescriptor0, simpleMethodName);
                    }
                } else {
                    final ServerMethodDefinition<?, ?> method =
                            service.getMethods().stream()
                                   .filter(method0 -> method0.getMethodDescriptor() == methodDescriptor)
                                   .findFirst()
                                   .orElseThrow(() -> new IllegalArgumentException(
                                           "Failed to retrieve " + methodDescriptor + " in " + service));
                    methods.put(path, method);
                    methodsByRoute.put(Route.builder().exact('/' + path).build(), method);
                    final MethodDescriptor<?, ?> methodDescriptor0 = method.getMethodDescriptor();
                    final String fullMethodName = methodDescriptor0.getFullMethodName();
                    simpleMethodNames.put(methodDescriptor0, extractMethodName(fullMethodName));
                }
            }
            return new HandlerRegistry(ImmutableList.copyOf(services.values()), ImmutableMap.copyOf(methods),
                                       ImmutableMap.copyOf(methodsByRoute),
                                       ImmutableMap.copyOf(simpleMethodNames));
        }
    }

    static final class Entry {
        private final String path;
        private final ServerServiceDefinition service;
        @Nullable
        private final MethodDescriptor<?, ?> method;

        Entry(String path, ServerServiceDefinition service, @Nullable MethodDescriptor<?, ?> method) {
            this.path = path;
            this.service = service;
            this.method = method;
        }

        String path() {
            return path;
        }

        ServerServiceDefinition service() {
            return service;
        }

        @Nullable
        MethodDescriptor<?, ?> method() {
            return method;
        }
    }
}
