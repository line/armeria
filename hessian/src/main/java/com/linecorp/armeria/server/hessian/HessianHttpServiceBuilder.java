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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.beans.Introspector;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

import com.google.common.base.Strings;
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

    private static final BiFunction<? super ServiceRequestContext, ? super Throwable, ? extends RpcResponse>
            defaultExceptionHandler = (
            ctx, cause) -> RpcResponse.ofFailure(cause);

    private static final Function<Object, Class<?>> defaultApiClassDetector = o -> {
        final Class<?>[] interfaces = o.getClass().getInterfaces();
        if (interfaces.length != 1) {
            throw new IllegalArgumentException(
                    "Expect hessian implementation " + o.getClass() + " to implement exactly one interface");
        }
        return interfaces[0];
    };

    private final ImmutableList.Builder<ServiceEntry> hessianService = ImmutableList.builder();

    private SerializationFormat defaultSerializationFormat = HESSIAN;

    private Function<Object, Class<?>> apiClassDetector;

    private Function<Class<?>, String> autoServicePathProducer = apiClass -> Introspector
            .decapitalize(apiClass.getSimpleName());

    private String prefix = "";

    private String suffix = "";

    @Nullable
    private Function<? super RpcService, ? extends RpcService> decoratorFunction;

    private BiFunction<? super ServiceRequestContext, ? super Throwable, ? extends RpcResponse>
            exceptionHandler = defaultExceptionHandler;

    HessianHttpServiceBuilder() {
        this.apiClassDetector = defaultApiClassDetector;
    }

    /**
     * The service route prefix, must start with '/'.
     */
    public HessianHttpServiceBuilder prefix(String prefix) {
        requireNonNull(prefix, "prefix");
        checkArgument(prefix.startsWith("/"), "prefix must start with '/', got +", prefix);
        this.prefix = prefix;
        return this;
    }

    /**
     * 服务的路由后缀缀。比如'.hs'
     */
    public HessianHttpServiceBuilder suffix(String suffix) {
        this.suffix = suffix;
        return this;
    }

    /**
     * 对对象来获取hessian的apiClass.
     */
    public HessianHttpServiceBuilder apiClassDetector(Function<Object, Class<?>> apiClassDetector) {
        this.apiClassDetector = apiClassDetector;
        return this;
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
        hessianService.add(new ServiceEntry(path, apiClass, implementation, true));
        return this;
    }

    /**
     * 增加服务.
     * @param path 路径。
     * @param implementation hessian的实现
     */
    public HessianHttpServiceBuilder addService(String path, Object implementation) {
        hessianService.add(new ServiceEntry(path, null, implementation, true));
        return this;
    }

    /**
     * 增加服务， 使用 {@link #autoServicePathProducer} 来确定path.
     * @param apiClass hessian的api
     * @param implementation hessian的实现
     */
    public HessianHttpServiceBuilder addService(Class<?> apiClass, Object implementation) {
        hessianService.add(new ServiceEntry(null, apiClass, implementation, true));
        return this;
    }

    /**
     * 增加服务， 使用 {@link #autoServicePathProducer} 来确定path. 增加服务. 使用
     * {@link #apiClassDetector} 来确定api 使用 {@link #autoServicePathProducer} 来确定path.
     * @param implementation hessian的实现
     */
    public HessianHttpServiceBuilder addService(Object implementation) {
        hessianService.add(new ServiceEntry(null, null, implementation, true));
        return this;
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
        final ImmutableList<ServiceEntry> implementations = hessianService.build();
        final ImmutableMap.Builder<String, HessianServiceMetadata> path2ServiceMapBuilder =
                ImmutableMap.builder();
        for (ServiceEntry entry : implementations) {
            final Class<?> apiClass = entry.apiClass != null ? entry.apiClass : apiClassDetector.apply(
                    entry.implementation);
            checkArgument(apiClass != null, "apiClass must not be null");
            final String path = entry.path != null ? entry.path : autoServicePathProducer.apply(apiClass);
            checkArgument(path != null, "path must not be null");
            final String fullPath = buildPath(path, prefix, suffix);
            final HessianServiceMetadata ssm = new HessianServiceMetadata(apiClass, entry.implementation,
                                                                          entry.blocking);
            path2ServiceMapBuilder.put(fullPath, ssm);
        }
        final HessianCallService hcs = HessianCallService.of(path2ServiceMapBuilder.build());
        return build0(hcs, path2ServiceMapBuilder.build());
    }

    private static String buildPath(String path, String prefix, String suffix) {
        String fullPath;
        if (Strings.isNullOrEmpty(prefix) || path.startsWith(prefix)) {
            fullPath = path;
        } else {
            fullPath = prefix + path;
            fullPath = fullPath.replaceAll("//", "/");
        }

        if (!Strings.isNullOrEmpty(suffix) && !path.endsWith(suffix)) {
            fullPath = fullPath + suffix;
        }
        if (fullPath.startsWith("/")) {
            return fullPath;
        } else {
            return '/' + fullPath;
        }
    }

    private HessianHttpServiceImpl build0(RpcService tcs,
                                          Map<String, HessianServiceMetadata> path2ServiceMap) {

        return new HessianHttpServiceImpl(decorate(tcs), defaultSerializationFormat, exceptionHandler);
    }

    private static final class ServiceEntry {

        @Nullable
        private final String path;

        @Nullable
        private final Class<?> apiClass;

        private final Object implementation;

        private final boolean blocking;

        private ServiceEntry(@Nullable String path, @Nullable Class<?> apiClass, Object implementation,
                             boolean blocking) {
            this.path = path;
            this.apiClass = apiClass;
            this.implementation = implementation;
            this.blocking = blocking;
        }
    }
}
