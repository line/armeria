/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.armeria.common.thrift;

import static java.util.Objects.requireNonNull;

import java.util.Set;

import org.apache.thrift.protocol.TProtocolFactory;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.annotation.Nullable;

/**
 * SPI Provider for links from {@link SerializationFormat} to {@link TProtocolFactory}.
 */
public abstract class ThriftProtocolFactoryProvider {
    /**
     * Pair of {@link SerializationFormat} and {@link TProtocolFactory}.
     */
    protected static final class Entry {
        final SerializationFormat serializationFormat;
        final TProtocolFactory protocolFactory;

        /**
         * Create an {@link Entry} with the specified {@link SerializationFormat} and {@link TProtocolFactory}.
         */
        public Entry(SerializationFormat serializationFormat, TProtocolFactory protocolFactory) {
            this.serializationFormat = requireNonNull(serializationFormat, "serializationFormat");
            this.protocolFactory = requireNonNull(protocolFactory, "protocolFactory");
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("serializationFormat", serializationFormat)
                              .add("protocolFactory", protocolFactory)
                              .toString();
        }
    }

    /**
     * Returns the supported Thrift-related {@link SerializationFormat}s.
     */
    protected abstract Set<SerializationFormat> serializationFormats();

    /**
     * Returns the {@link TProtocolFactory} for the specified {@link SerializationFormat},
     * {@code maxStringLength} and {@code maxContainerLength}.
     * Returns {@code null} if the {@link SerializationFormat} is unsupported.
     *
     * @param serializationFormat the serialization format that the {@link TProtocolFactory} supports.
     * @param maxStringLength the maximum allowed number of bytes to read from the transport for
     *                        variable-length fields (such as strings or binary). {@code 0} means unlimited.
     * @param maxContainerLength the maximum allowed number of containers to read from the transport for
     *                           maps, sets and lists. {@code 0} means unlimited.
     */
    @Nullable
    protected abstract TProtocolFactory protocolFactory(SerializationFormat serializationFormat,
                                                        int maxStringLength, int maxContainerLength);
}
