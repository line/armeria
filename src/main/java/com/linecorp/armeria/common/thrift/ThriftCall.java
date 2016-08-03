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

package com.linecorp.armeria.common.thrift;

import static java.util.Objects.requireNonNull;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;

import org.apache.thrift.TBase;
import org.apache.thrift.TFieldIdEnum;
import org.apache.thrift.meta_data.FieldMetaData;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.AbstractRpcRequest;
import com.linecorp.armeria.common.RpcRequest;

/**
 * A Thrift {@link RpcRequest}.
 */
public final class ThriftCall extends AbstractRpcRequest {

    private final int seqId;

    /**
     * Creates a new instance.
     */
    public ThriftCall(int seqId, Class<?> serviceType, String method, Iterable<?> args) {
        this(seqId, serviceType, method, ImmutableList.copyOf(args));
    }

    /**
     * Creates a new instance.
     */
    public ThriftCall(int seqId, Class<?> serviceType, String method, Object... args) {
        this(seqId, serviceType, method, ImmutableList.copyOf(args));
    }

    /**
     * Creates a new instance.
     */
    public ThriftCall(int seqId, Class<?> serviceType, String method, TBase<?, ?> thriftArgs) {
        this(seqId, serviceType, method, toList(thriftArgs));
    }

    @Nonnull
    private static List<Object> toList(TBase<?, ?> thriftArgs) {
        requireNonNull(thriftArgs, "thriftArgs");

        @SuppressWarnings("unchecked")
        final TBase<TBase<?, ?>, TFieldIdEnum> castThriftArgs = (TBase<TBase<?, ?>, TFieldIdEnum>) thriftArgs;
        return Collections.unmodifiableList(
                FieldMetaData.getStructMetaDataMap(castThriftArgs.getClass()).keySet().stream()
                             .map(castThriftArgs::getFieldValue).collect(Collectors.toList()));
    }

    private ThriftCall(int seqId, Class<?> serviceType, String method, List<Object> args) {
        super(serviceType, method, args);
        this.seqId = seqId;
    }

    /**
     * Returns the {@code seqId} of this call.
     */
    public int seqId() {
        return seqId;
    }

    @Override
    public int hashCode() {
        return (seqId * 31 + method().hashCode()) * 31 + params().hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof ThriftCall)) {
            return false;
        }

        if (this == obj) {
            return true;
        }

        final ThriftCall that = (ThriftCall) obj;
        return seqId() == that.seqId() &&
               method().equals(that.method()) &&
               params().equals(that.params());
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("seqId", seqId())
                          .add("serviceType", simpleServiceName())
                          .add("method", method())
                          .add("args", params()).toString();
    }
}
