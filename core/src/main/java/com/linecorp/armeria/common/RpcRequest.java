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

package com.linecorp.armeria.common;

import static com.linecorp.armeria.common.DefaultRpcRequest.SINGLE_NULL_PARAM;
import static java.util.Objects.requireNonNull;

import java.util.List;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;

/**
 * An RPC {@link Request}.
 */
public interface RpcRequest extends Request {

    /**
     * Creates a new instance with no parameter.
     */
    static RpcRequest of(Class<?> serviceType, String method) {
        return new DefaultRpcRequest(serviceType, null, method, ImmutableList.of());
    }

    /**
     * Creates a new instance with a single parameter.
     */
    static RpcRequest of(Class<?> serviceType, String method, @Nullable Object parameter) {
        final List<Object> parameters = parameter == null ? SINGLE_NULL_PARAM
                                                          : ImmutableList.of(parameter);
        return new DefaultRpcRequest(serviceType, null, method, parameters);
    }

    /**
     * Creates a new instance with the specified parameters.
     */
    static RpcRequest of(Class<?> serviceType, String method, Iterable<?> params) {
        return of(serviceType, null, method, params);
    }

    /**
     * Creates a new instance with the specified parameters.
     */
    static RpcRequest of(Class<?> serviceType, @Nullable String serviceName, String method,
                         Iterable<?> params) {
        requireNonNull(params, "params");
        return new DefaultRpcRequest(serviceType, serviceName, method, params);
    }

    /**
     * Creates a new instance with the specified parameters.
     */
    static RpcRequest of(Class<?> serviceType, String method, Object... params) {
        requireNonNull(params, "params");
        return new DefaultRpcRequest(serviceType, null, method, params);
    }

    /**
     * Returns the type of the service this {@link RpcRequest} is called upon.
     */
    Class<?> serviceType();

    /**
     * Returns the name of the service this {@link RpcRequest} is called upon.
     */
    default String serviceName() {
        return serviceType().getName();
    }

    /**
     * Returns the method name.
     */
    String method();

    /**
     * Returns the parameters.
     */
    List<Object> params();
}
