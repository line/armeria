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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;
import static org.reflections.ReflectionUtils.withModifier;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.CaseFormat;
import com.google.common.base.Converter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.DependencyInjector;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.grpc.GrpcExceptionHandlerFunction;
import com.linecorp.armeria.internal.common.ReflectiveDependencyInjector;
import com.linecorp.armeria.internal.common.grpc.UnwrappingGrpcExceptionHandleFunction;
import com.linecorp.armeria.internal.server.annotation.AnnotationUtil;
import com.linecorp.armeria.internal.server.annotation.DecoratorAnnotationUtil;
import com.linecorp.armeria.internal.server.annotation.DecoratorAnnotationUtil.DecoratorAndOrder;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.annotation.Blocking;

import io.grpc.MethodDescriptor;
import io.grpc.ServerMethodDefinition;
import io.grpc.ServerServiceDefinition;

/**
 * A registry of the implementation methods bound to a {@link GrpcService}. Used for method dispatch and
 * documentation generation.
 */
final class HandlerRegistry {

    // e.g. UnaryCall -> unaryCall
    private static final Converter<String, String> methodNameConverter =
            CaseFormat.UPPER_CAMEL.converterTo(CaseFormat.LOWER_CAMEL);

    private final List<ServerServiceDefinition> services;
    private final Map<String, ServerMethodDefinition<?, ?>> methods;
    private final Map<Route, ServerMethodDefinition<?, ?>> methodsByRoute;
    private final Map<MethodDescriptor<?, ?>, String> simpleMethodNames;
    private final Map<ServerMethodDefinition<?, ?>, List<DecoratorAndOrder>> annotationDecorators;
    private final Map<ServerMethodDefinition<?, ?>, List<? extends Function<? super HttpService,
            ? extends HttpService>>> additionalDecorators;
    private final Set<ServerMethodDefinition<?, ?>> blockingMethods;
    private final Map<ServerMethodDefinition<?, ?>, GrpcExceptionHandlerFunction> grpcExceptionHandlers;

    @Nullable
    private final GrpcExceptionHandlerFunction defaultExceptionHandler;

    private HandlerRegistry(List<ServerServiceDefinition> services,
                            Map<String, ServerMethodDefinition<?, ?>> methods,
                            Map<Route, ServerMethodDefinition<?, ?>> methodsByRoute,
                            Map<MethodDescriptor<?, ?>, String> simpleMethodNames,
                            Map<ServerMethodDefinition<?, ?>, List<DecoratorAndOrder>> annotationDecorators,
                            Map<ServerMethodDefinition<?, ?>, List<? extends Function<? super HttpService,
                                    ? extends HttpService>>> additionalDecorators,
                            Set<ServerMethodDefinition<?, ?>> blockingMethods,
                            Map<ServerMethodDefinition<?, ?>, GrpcExceptionHandlerFunction>
                                    grpcExceptionHandlers,
                            @Nullable GrpcExceptionHandlerFunction defaultExceptionHandler) {
        this.services = requireNonNull(services, "services");
        this.methods = requireNonNull(methods, "methods");
        this.methodsByRoute = requireNonNull(methodsByRoute, "methodsByRoute");
        this.simpleMethodNames = requireNonNull(simpleMethodNames, "simpleMethodNames");
        this.annotationDecorators = requireNonNull(annotationDecorators, "annotationDecorators");
        this.additionalDecorators = requireNonNull(additionalDecorators, "additionalDecorators");
        this.blockingMethods = requireNonNull(blockingMethods, "blockingMethods");
        this.grpcExceptionHandlers = requireNonNull(grpcExceptionHandlers, "grpcExceptionHandlers");
        this.defaultExceptionHandler = defaultExceptionHandler;
    }

    @Nullable
    ServerMethodDefinition<?, ?> lookupMethod(String methodName) {
        return methods.get(methodName);
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

    @VisibleForTesting
    Map<ServerMethodDefinition<?, ?>, List<DecoratorAndOrder>> annotationDecorators() {
        return annotationDecorators;
    }

    boolean containsDecorators() {
        return !annotationDecorators.isEmpty() || !additionalDecorators.isEmpty();
    }

    boolean needToUseBlockingTaskExecutor(ServerMethodDefinition<?, ?> methodDef) {
        return blockingMethods.contains(methodDef);
    }

    @Nullable
    GrpcExceptionHandlerFunction getExceptionHandler(ServerMethodDefinition<?, ?> methodDef) {
        if (!grpcExceptionHandlers.containsKey(methodDef)) {
            return defaultExceptionHandler;
        }
        return grpcExceptionHandlers.get(methodDef);
    }

    Map<ServerMethodDefinition<?, ?>, HttpService> applyDecorators(
            HttpService delegate, DependencyInjector dependencyInjector) {
        final Map<ServerMethodDefinition<?, ?>, HttpService> decorated = new HashMap<>();

        for (Map.Entry<ServerMethodDefinition<?, ?>, List<DecoratorAndOrder>> entry
                : annotationDecorators.entrySet()) {
            final List<? extends Function<? super HttpService, ? extends HttpService>> decorators =
                    entry.getValue()
                         .stream()
                         .map(decoratorAndOrder -> decoratorAndOrder.decorator(dependencyInjector))
                         .collect(toImmutableList());
            decorated.put(entry.getKey(), applyDecorators(decorators, delegate));
        }

        for (Map.Entry<ServerMethodDefinition<?, ?>, List<? extends Function<? super HttpService,
                ? extends HttpService>>> entry : additionalDecorators.entrySet()) {
            final HttpService service = decorated.getOrDefault(entry.getKey(), delegate);
            decorated.put(entry.getKey(), applyDecorators(entry.getValue(), service));
        }

        return ImmutableMap.copyOf(decorated);
    }

    private static HttpService applyDecorators(
            Iterable<? extends Function<? super HttpService, ? extends HttpService>> decorators,
            HttpService delegate) {
        Function<? super HttpService, ? extends HttpService> decorator = Function.identity();
        for (Function<? super HttpService, ? extends HttpService> function : decorators) {
            decorator = decorator.compose(function);
        }
        return decorator.apply(delegate);
    }

    static final class Builder {
        private final List<Entry> entries = new ArrayList<>();

        @Nullable
        private GrpcExceptionHandlerFunction defaultExceptionHandler;

        Builder addService(ServerServiceDefinition service, @Nullable Class<?> type,
                           List<? extends Function<? super HttpService,
                                   ? extends HttpService>> additionalDecorators) {
            requireNonNull(service, "service");
            requireNonNull(additionalDecorators, "additionalDecorators");
            entries.add(new Entry(service.getServiceDescriptor().getName(), service, null, type,
                                  additionalDecorators));
            return this;
        }

        Builder addService(String path, ServerServiceDefinition service,
                           @Nullable MethodDescriptor<?, ?> methodDescriptor, @Nullable Class<?> type,
                           List<? extends Function<? super HttpService,
                                   ? extends HttpService>> additionalDecorators) {
            requireNonNull(path, "path");
            requireNonNull(service, "service");
            requireNonNull(additionalDecorators, "additionalDecorators");

            entries.add(new Entry(normalizePath(path, methodDescriptor == null), service,
                                  methodDescriptor, type, additionalDecorators));
            return this;
        }

        Builder setDefaultExceptionHandler(GrpcExceptionHandlerFunction defaultExceptionHandler) {
            requireNonNull(defaultExceptionHandler, "defaultExceptionHandler");
            this.defaultExceptionHandler = defaultExceptionHandler;
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

        private static boolean needToUseBlockingTaskExecutor(Class<?> clazz, Method method) {
            return AnnotationUtil.findFirst(method, Blocking.class) != null ||
                   AnnotationUtil.findFirst(clazz, Blocking.class) != null;
        }

        private static void putGrpcExceptionHandlerIfPresent(
                Class<?> clazz, Method method, DependencyInjector dependencyInjector,
                ServerMethodDefinition<?, ?> methodDefinition,
                final ImmutableMap.Builder<ServerMethodDefinition<?, ?>, GrpcExceptionHandlerFunction>
                        grpcExceptionHandlersBuilder,
                @Nullable GrpcExceptionHandlerFunction defaultExceptionHandler) {
            final List<GrpcExceptionHandlerFunction> exceptionHandlers =
                    AnnotationUtil.getAnnotatedInstances(method, clazz,
                                                         GrpcExceptionHandler.class,
                                                         GrpcExceptionHandlerFunction.class,
                                                         dependencyInjector).build();
            final Optional<GrpcExceptionHandlerFunction> grpcExceptionHandler =
                    exceptionHandlers.stream().reduce(GrpcExceptionHandlerFunction::orElse);

            grpcExceptionHandler.ifPresent(exceptionHandler -> {
                GrpcExceptionHandlerFunction grpcExceptionHandler0 = exceptionHandler;
                if (defaultExceptionHandler != null) {
                    grpcExceptionHandler0 = new UnwrappingGrpcExceptionHandleFunction(
                            exceptionHandler.orElse(defaultExceptionHandler));
                }
                grpcExceptionHandlersBuilder.put(methodDefinition, grpcExceptionHandler0);
            });
        }

        List<Entry> entries() {
            return entries;
        }

        HandlerRegistry build() {
            // Store per-service first, to make sure services are added/replaced atomically.
            final ImmutableMap.Builder<String, ServerServiceDefinition> services = ImmutableMap.builder();
            final ImmutableMap.Builder<String, ServerMethodDefinition<?, ?>> methods = ImmutableMap.builder();
            final ImmutableMap.Builder<Route, ServerMethodDefinition<?, ?>> methodsByRoute =
                    ImmutableMap.builder();
            final ImmutableMap.Builder<MethodDescriptor<?, ?>, String> bareMethodNames = ImmutableMap.builder();
            final ImmutableMap.Builder<ServerMethodDefinition<?, ?>, List<DecoratorAndOrder>>
                    annotationDecorators = ImmutableMap.builder();
            final ImmutableMap.Builder<ServerMethodDefinition<?, ?>,
                    List<? extends Function<? super HttpService, ? extends HttpService>>>
                    additionalDecoratorsBuilder = ImmutableMap.builder();
            final ImmutableSet.Builder<ServerMethodDefinition<?, ?>> blockingMethods =
                    ImmutableSet.builder();
            final ImmutableMap.Builder<ServerMethodDefinition<?, ?>, GrpcExceptionHandlerFunction>
                    grpcExceptionHandlersBuilder = ImmutableMap.builder();
            final DependencyInjector dependencyInjector = new ReflectiveDependencyInjector();
            for (Entry entry : entries) {
                final ServerServiceDefinition service = entry.service();
                final String path = entry.path();
                services.put(path, service);
                final MethodDescriptor<?, ?> methodDescriptor = entry.method();
                final List<? extends Function<? super HttpService, ? extends HttpService>>
                        additionalDecorators = entry.additionalDecorators();
                if (methodDescriptor == null) {
                    final Class<?> type = entry.type();
                    final Map<String, Method> publicMethods = new HashMap<>();
                    if (type != null) {
                        for (Method method : InternalReflectionUtils.getAllSortedMethods(
                                type, withModifier(Modifier.PUBLIC))) {
                            final String methodName = method.getName();
                            if (!publicMethods.containsKey(methodName)) {
                                publicMethods.put(methodName, method);
                            }
                        }
                    }

                    for (ServerMethodDefinition<?, ?> methodDefinition : service.getMethods()) {
                        final MethodDescriptor<?, ?> methodDescriptor0 = methodDefinition.getMethodDescriptor();
                        final String bareMethodName = methodDescriptor0.getBareMethodName();
                        assert bareMethodName != null;
                        final String pathWithMethod = path + '/' + bareMethodName;
                        methods.put(pathWithMethod, methodDefinition);
                        methodsByRoute.put(Route.builder().exact('/' + pathWithMethod).build(),
                                           methodDefinition);
                        bareMethodNames.put(methodDescriptor0, bareMethodName);
                        if (!additionalDecorators.isEmpty()) {
                            additionalDecoratorsBuilder.put(methodDefinition, additionalDecorators);
                        }
                        final String methodName = methodNameConverter.convert(bareMethodName);
                        final Method method = publicMethods.get(methodName);
                        if (method != null) {
                            assert type != null;
                            final List<DecoratorAndOrder> decoratorAndOrders =
                                    DecoratorAnnotationUtil.collectDecorators(type, method);
                            if (!decoratorAndOrders.isEmpty()) {
                                annotationDecorators.put(methodDefinition, decoratorAndOrders);
                            }
                            if (needToUseBlockingTaskExecutor(type, method)) {
                                blockingMethods.add(methodDefinition);
                            }
                            putGrpcExceptionHandlerIfPresent(type, method, dependencyInjector,
                                                             methodDefinition, grpcExceptionHandlersBuilder,
                                                             defaultExceptionHandler);
                        }
                    }
                } else {
                    final ServerMethodDefinition<?, ?> methodDefinition =
                            service.getMethods().stream()
                                   .filter(method0 -> method0.getMethodDescriptor() == methodDescriptor)
                                   .findFirst()
                                   .orElseThrow(() -> new IllegalArgumentException(
                                           "Failed to retrieve " + methodDescriptor + " in " + service));
                    methods.put(path, methodDefinition);
                    additionalDecoratorsBuilder.put(methodDefinition, additionalDecorators);
                    methodsByRoute.put(Route.builder().exact('/' + path).build(), methodDefinition);
                    final MethodDescriptor<?, ?> methodDescriptor0 = methodDefinition.getMethodDescriptor();
                    final String bareMethodName = methodDescriptor0.getBareMethodName();
                    assert bareMethodName != null;
                    bareMethodNames.put(methodDescriptor0, bareMethodName);
                    final Class<?> type = entry.type();
                    if (type != null) {
                        final String methodName = methodNameConverter.convert(bareMethodName);
                        final Optional<Method> method =
                                InternalReflectionUtils.getAllSortedMethods(type, withModifier(Modifier.PUBLIC))
                                                       .stream()
                                                       .filter(m -> methodName.equals(m.getName()))
                                                       .findFirst();
                        if (method.isPresent()) {
                            final Method method0 = method.get();
                            final List<DecoratorAndOrder> decoratorAndOrders =
                                    DecoratorAnnotationUtil.collectDecorators(type, method0);
                            if (!decoratorAndOrders.isEmpty()) {
                                annotationDecorators.put(methodDefinition, decoratorAndOrders);
                            }
                            if (needToUseBlockingTaskExecutor(type, method0)) {
                                blockingMethods.add(methodDefinition);
                            }
                            putGrpcExceptionHandlerIfPresent(type, method0, dependencyInjector,
                                                             methodDefinition, grpcExceptionHandlersBuilder,
                                                             defaultExceptionHandler);
                        }
                    }
                }
            }
            final Map<String, ServerServiceDefinition> services0 = services.build();
            return new HandlerRegistry(ImmutableList.copyOf(services0.values()),
                                       methods.build(),
                                       methodsByRoute.build(),
                                       bareMethodNames.buildKeepingLast(),
                                       annotationDecorators.build(),
                                       additionalDecoratorsBuilder.build(),
                                       blockingMethods.build(),
                                       grpcExceptionHandlersBuilder.build(),
                                       defaultExceptionHandler);
        }
    }

    static final class Entry {
        private final String path;
        private final ServerServiceDefinition service;
        @Nullable
        private final MethodDescriptor<?, ?> method;
        @Nullable
        private final Class<?> type;

        private final List<? extends Function<? super HttpService, ? extends HttpService>>
                additionalDecorators;

        Entry(String path, ServerServiceDefinition service,
              @Nullable MethodDescriptor<?, ?> method, @Nullable Class<?> type,
              List<? extends Function<? super HttpService,
                      ? extends HttpService>> additionalDecorators) {
            this.path = path;
            this.service = service;
            this.method = method;
            this.type = type;
            this.additionalDecorators = additionalDecorators;
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

        @Nullable
        Class<?> type() {
            return type;
        }

        List<? extends Function<? super HttpService, ? extends HttpService>> additionalDecorators() {
            return additionalDecorators;
        }
    }
}
