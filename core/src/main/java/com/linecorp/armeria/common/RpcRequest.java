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

import static java.util.Objects.requireNonNull;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;

/**
 * An RPC {@link Request}.
 */
public interface RpcRequest extends Request {

    /**
     * Creates a new instance with no parameter.
     */
    static RpcRequest of(Class<?> serviceType, String method) {
        return of(serviceType, null, method);
    }

    /**
     * Creates a new instance with no parameter.
     */
    static RpcRequest of(Class<?> serviceType, @Nullable String serviceName, String method) {
        return new DefaultRpcRequest(serviceType, serviceName, method, Collections.emptyList());
    }

    /**
     * Creates a new instance with a single parameter.
     */
    static RpcRequest of(Class<?> serviceType, String method, @Nullable Object parameter) {
        return of(serviceType, null, method, parameter);
    }

    /**
     * Creates a new instance with a single parameter.
     */
    static RpcRequest of(Class<?> serviceType, @Nullable String serviceName, String method,
                         @Nullable Object parameter) {
        return new DefaultRpcRequest(serviceType, serviceName, method, Collections.singletonList(parameter));
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

        if (!(params instanceof Collection)) {
            return new DefaultRpcRequest(serviceType, serviceName, method, params);
        }

        final Collection<?> paramCollection = (Collection<?>) params;
        switch (paramCollection.size()) {
            case 0:
                return of(serviceType, method);
            case 1:
                if (paramCollection instanceof List) {
                    return of(serviceType, method, ((List<?>) paramCollection).get(0));
                } else {
                    return of(serviceType, method, paramCollection.iterator().next());
                }
            default:
                return new DefaultRpcRequest(serviceType, serviceName, method, paramCollection.toArray());
        }
    }

    /**
     * Creates a new instance with the specified parameters.
     */
    static RpcRequest of(Class<?> serviceType, String method, Object... params) {
        return of(serviceType, null, method, params);
    }

    /**
     * Creates a new instance with the specified parameters.
     */
    static RpcRequest of(Class<?> serviceType, @Nullable String serviceName, String method, Object... params) {
        requireNonNull(params, "params");
        switch (params.length) {
            case 0:
                return of(serviceType, serviceName, method);
            case 1:
                return of(serviceType, serviceName, method, params[0]);
            default:
                return new DefaultRpcRequest(serviceType, serviceName, method, params);
        }
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
