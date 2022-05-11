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

package com.linecorp.armeria.server.hessian;

import static java.util.Objects.requireNonNull;

import java.util.function.BiFunction;
import java.util.function.Function;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.internal.server.hessian.HessianCallService;
import com.linecorp.armeria.internal.server.hessian.HessianHttpServiceImpl;
import com.linecorp.armeria.internal.server.hessian.HessianServiceMetadata;
import com.linecorp.armeria.server.RpcService;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * The builder of {@link HessianHttpService}.
 *
 * <h2>Example</h2> <pre>{@code
 * Server server =
 *     Server.builder()
 *           .http(8080)
 *           .service("/", HessianHttpService.builder()
 *                                     .addService(new FooServiceImpl())              //
 *                                     .addService(BarService.class, new BarServiceImpl())
 *                                     .addService("foobar", new FooBarServiceImpl()) //
 *                                     .build())
 *           .build();
 * }</pre>
 */
@UnstableApi
public final class HessianHttpServiceBuilder {

    static final SerializationFormat HESSIAN = SerializationFormat.of("hessian");

    private static final SerializationFormat defaultSerializationFormat = HESSIAN;

    private static final BiFunction<? super ServiceRequestContext, ? super Throwable, ? extends RpcResponse>
            defaultExceptionHandler = (
            ctx, cause) -> RpcResponse.ofFailure(cause);

    private final ImmutableList.Builder<ServiceEntry> hessianService = ImmutableList.builder();

    @Nullable
    private Function<? super RpcService, ? extends RpcService> decoratorFunction;

    private BiFunction<? super ServiceRequestContext, ? super Throwable, ? extends RpcResponse>
            exceptionHandler = defaultExceptionHandler;

    HessianHttpServiceBuilder() {
    }

    /**
     * 增加服务.
     * @param path 路径。
     * @param apiClass hessian的api
     * @param implementation hessian的实现
     * @param blocking 是否非阻塞的实现
     */
    public HessianHttpServiceBuilder addService(String path, Class<?> apiClass, Object implementation,
                                                boolean blocking) {
        requireNonNull(path, "path");
        requireNonNull(apiClass, "apiClass");
        requireNonNull(implementation, "implementation");
        hessianService.add(new ServiceEntry(path, apiClass, implementation, blocking));
        return this;
    }

    /**
     * 增加服务.
     * @param path 路径。
     * @param apiClass hessian的api
     * @param implementation hessian的实现
     */
    public HessianHttpServiceBuilder addService(String path, Class<?> apiClass, Object implementation) {
        return addService(path, apiClass, implementation, true);
    }

    /**
     * Sets the {@link BiFunction} that returns an {@link RpcResponse} using the given
     * {@link Throwable} and {@link ServiceRequestContext}.
     */
    public HessianHttpServiceBuilder exceptionHandler(
            BiFunction<? super ServiceRequestContext, ? super Throwable, ? extends RpcResponse>
                    exceptionHandler) {
        this.exceptionHandler = requireNonNull(exceptionHandler, "exceptionHandler");
        return this;
    }

    /**
     * A {@code Function<? super RpcService, ? extends RpcService>} to decorate the
     * {@link RpcService}.
     */
    public HessianHttpServiceBuilder decorate(
            Function<? super RpcService, ? extends RpcService> decoratorFunction) {
        requireNonNull(decoratorFunction, "decoratorFunction");
        if (this.decoratorFunction == null) {
            this.decoratorFunction = decoratorFunction;
        } else {
            this.decoratorFunction = this.decoratorFunction.andThen(decoratorFunction);
        }
        return this;
    }

    private RpcService decorate(RpcService service) {
        if (decoratorFunction != null) {
            return service.decorate(decoratorFunction);
        }
        return service;
    }

    /**
     * Builds a new instance of {@link HessianHttpServiceImpl}.
     */
    public HessianHttpServiceImpl build() {
        @SuppressWarnings("UnstableApiUsage")
        final ImmutableList<ServiceEntry> serviceEntries = hessianService.build();
        final ImmutableMap.Builder<String, HessianServiceMetadata> path2ServiceMapBuilder =
                ImmutableMap.builder();
        for (ServiceEntry entry : serviceEntries) {
            final HessianServiceMetadata ssm = new HessianServiceMetadata(entry.apiClass, entry.implementation,
                                                                          entry.blocking);
            path2ServiceMapBuilder.put(entry.path, ssm);
        }
        final HessianCallService hcs = HessianCallService.of(path2ServiceMapBuilder.build());
        return build0(hcs);
    }

    private HessianHttpServiceImpl build0(RpcService tcs) {
        return new HessianHttpServiceImpl(decorate(tcs), defaultSerializationFormat, exceptionHandler);
    }

    private static final class ServiceEntry {

        private final String path;

        private final Class<?> apiClass;

        private final Object implementation;

        private final boolean blocking;

        private ServiceEntry(String path, Class<?> apiClass, Object implementation,
                             boolean blocking) {
            this.path = path;
            this.apiClass = apiClass;
            this.implementation = implementation;
            this.blocking = blocking;
        }
    }
}
