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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;

/**
 * Default {@link RpcRequest} implementation.
 */
public class DefaultRpcRequest implements RpcRequest {

    private final Class<?> serviceType;
    private final String method;
    private final List<Object> params;

    /**
     * Creates a new instance with no parameter.
     */
    public DefaultRpcRequest(Class<?> serviceType, String method) {
        this(serviceType, method, Collections.emptyList());
    }

    /**
     * Creates a new instance with a single parameter.
     */
    public DefaultRpcRequest(Class<?> serviceType, String method, @Nullable Object parameter) {
        this(serviceType, method, Collections.singletonList(parameter));
    }

    /**
     * Creates a new instance with the specified parameters.
     */
    public DefaultRpcRequest(Class<?> serviceType, String method, Iterable<?> params) {
        this(serviceType, method, copyParams(params));
    }

    /**
     * Creates a new instance with the specified parameters.
     */
    public DefaultRpcRequest(Class<?> serviceType, String method, Object... params) {
        this(serviceType, method, copyParams(params));
    }

    private DefaultRpcRequest(Class<?> serviceType, String method, List<Object> params) {
        this.serviceType = requireNonNull(serviceType, "serviceType");
        this.method = requireNonNull(method, "method");
        this.params = params;
    }

    private static List<Object> copyParams(Iterable<?> params) {
        requireNonNull(params, "params");

        // Note we do not use ImmutableList.copyOf() here,
        // because it does not allow a null element and we should allow a null argument.
        final List<Object> copy;
        if (params instanceof Collection) {
            copy = new ArrayList<>(((Collection<?>) params).size());
        } else {
            copy = new ArrayList<>(8);
        }

        for (Object p : params) {
            copy.add(p);
        }

        return Collections.unmodifiableList(copy);
    }

    private static List<Object> copyParams(Object... params) {
        return Collections.unmodifiableList(Arrays.asList(requireNonNull(params, "params")));
    }

    @Override
    public final Class<?> serviceType() {
        return serviceType;
    }

    @Override
    public final String method() {
        return method;
    }

    @Override
    public final List<Object> params() {
        return params;
    }

    @Override
    public int hashCode() {
        return method().hashCode() * 31 + params().hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (!(obj instanceof DefaultRpcRequest)) {
            return false;
        }

        final DefaultRpcRequest that = (DefaultRpcRequest) obj;
        return method().equals(that.method()) &&
               params().equals(that.params());
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("serviceType", simpleServiceName())
                          .add("method", method())
                          .add("params", params()).toString();
    }

    /**
     * Returns the simplified name of the {@link #serviceType()}.
     */
    protected final String simpleServiceName() {
        final Class<?> serviceType = serviceType();
        final String fqcn = serviceType.getName();
        final int lastDot = fqcn.lastIndexOf('.');
        return lastDot < 0 ? fqcn : fqcn.substring(lastDot + 1);
    }
}
