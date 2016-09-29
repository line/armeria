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

package com.linecorp.armeria.common;

import static java.util.Objects.requireNonNull;

import java.util.List;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;

/**
 * A skeletal {@link RpcRequest} implementation.
 */
public class AbstractRpcRequest implements RpcRequest {

    private final Class<?> serviceType;
    private final String method;
    private final List<Object> args;

    /**
     * Creates a new instance.
     */
    protected AbstractRpcRequest(Class<?> serviceType, String method, Iterable<?> args) {
        this(serviceType, method, ImmutableList.copyOf(args));
    }

    /**
     * Creates a new instance.
     */
    protected AbstractRpcRequest(Class<?> serviceType, String method, Object... args) {
        this(serviceType, method, ImmutableList.copyOf(args));
    }

    /**
     * Creates a new instance.
     */
    protected AbstractRpcRequest(Class<?> serviceType, String method, List<Object> args) {
        this.serviceType = requireNonNull(serviceType, "serviceType");
        this.method = requireNonNull(method, "method");
        this.args = args;
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
        return args;
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

        if (!(obj instanceof AbstractRpcRequest)) {
            return false;
        }

        final AbstractRpcRequest that = (AbstractRpcRequest) obj;
        return method().equals(that.method()) &&
               params().equals(that.params());
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("serviceType", simpleServiceName())
                          .add("method", method())
                          .add("args", params()).toString();
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
