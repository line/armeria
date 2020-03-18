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

package com.linecorp.armeria.client;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.function.Function;

import org.reflections.ReflectionUtils;

import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;

/**
 * A set of {@link Function}s that transforms a {@link Client} into another.
 */
public final class ClientDecoration {

    private static final ClientDecoration NONE = new ClientDecoration(ImmutableList.of(), ImmutableList.of());

    private static final Predicate<Method> isOverriddenAsMethod = method -> {
        final Class<?>[] parameterTypes = method.getParameterTypes();
        return "as".equals(method.getName()) &&
               Modifier.isPublic(method.getModifiers()) && !method.isDefault() &&
               parameterTypes.length == 1 && parameterTypes[0].getName().equals(Class.class.getName());
    };

    /**
     * Validates whether the specified {@code decorator} overrides {@link Client#as(Class)} properly.
     */
    static <I extends Request, O extends Response> void validateDecorator(Client<I, O> decorator) {
        if (ReflectionUtils.getAllMethods(decorator.getClass(), isOverriddenAsMethod).isEmpty()) {
            throw new IllegalArgumentException("decorator should override Client.as(): " + decorator);
        }
    }

    /**
     * Returns an empty {@link ClientDecoration} which does not decorate a {@link Client}.
     */
    public static ClientDecoration of() {
        return NONE;
    }

    /**
     * Creates a new instance from a single decorator {@link Function}.
     *
     * @param decorator the {@link Function} that transforms an {@link HttpClient} to another
     */
    public static ClientDecoration of(Function<? super HttpClient, ? extends HttpClient> decorator) {
        return builder().add(decorator).build();
    }

    /**
     * Creates a new instance from a single {@link DecoratingHttpClientFunction}.
     *
     * @param decorator the {@link DecoratingHttpClientFunction} that transforms an {@link HttpClient}
     *                  to another
     */
    public static ClientDecoration of(DecoratingHttpClientFunction decorator) {
        return builder().add(decorator).build();
    }

    /**
     * Creates a new instance from a single decorator {@link Function}.
     *
     * @param decorator the {@link Function} that transforms an {@link RpcClient} to another
     */
    public static ClientDecoration ofRpc(Function<? super RpcClient, ? extends RpcClient> decorator) {
        return builder().addRpc(decorator).build();
    }

    /**
     * Creates a new instance from a single {@link DecoratingRpcClientFunction}.
     *
     * @param decorator the {@link DecoratingRpcClientFunction} that transforms an {@link RpcClient} to another
     */
    public static ClientDecoration ofRpc(DecoratingRpcClientFunction decorator) {
        return builder().addRpc(decorator).build();
    }

    /**
     * Returns a newly created {@link ClientDecorationBuilder}.
     */
    public static ClientDecorationBuilder builder() {
        return new ClientDecorationBuilder();
    }

    private final List<Function<? super HttpClient, ? extends HttpClient>> decorators;
    private final List<Function<? super RpcClient, ? extends RpcClient>> rpcDecorators;

    ClientDecoration(List<Function<? super HttpClient, ? extends HttpClient>> decorators,
                     List<Function<? super RpcClient, ? extends RpcClient>> rpcDecorators) {
        this.decorators = ImmutableList.copyOf(decorators);
        this.rpcDecorators = ImmutableList.copyOf(rpcDecorators);
    }

    List<Function<? super HttpClient, ? extends HttpClient>> decorators() {
        return decorators;
    }

    List<Function<? super RpcClient, ? extends RpcClient>> rpcDecorators() {
        return rpcDecorators;
    }

    boolean isEmpty() {
        return decorators.isEmpty() && rpcDecorators.isEmpty();
    }

    /**
     * Decorates the specified {@link HttpClient} using the decorator.
     *
     * @param client the {@link HttpClient} being decorated
     */
    public HttpClient decorate(HttpClient client) {
        for (Function<? super HttpClient, ? extends HttpClient> decorator : decorators) {
            client = decorator.apply(client);
            validateDecorator(client);
        }
        return client;
    }

    /**
     * Decorates the specified {@link RpcClient} using the decorator.
     *
     * @param client the {@link RpcClient} being decorated
     */
    public RpcClient rpcDecorate(RpcClient client) {
        for (Function<? super RpcClient, ? extends RpcClient> decorator : rpcDecorators) {
            client = decorator.apply(client);
            validateDecorator(client);
        }
        return client;
    }
}
