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

import com.linecorp.armeria.common.SerializationFormat;

/**
 * SPI Provider for links from {@link SerializationFormat} to {@link TProtocolFactory}.
 */
public abstract class ThriftProtocolFactoryProvider {
    /**
     * Pair of {@link SerializationFormat} and {@link TProtocolFactory}.
     */
    protected static final class Entry {
        private final SerializationFormat serializationFormat;
        private final TProtocolFactory tProtocolFactory;

        public Entry(SerializationFormat serializationFormat, TProtocolFactory tProtocolFactory) {
            this.serializationFormat = requireNonNull(serializationFormat, "serializationFormat");
            this.tProtocolFactory = requireNonNull(tProtocolFactory, "tProtocolFactory");
        }

        public SerializationFormat getSerializationFormat() {
            return serializationFormat;
        }

        public TProtocolFactory getTProtocolFactory() {
            return tProtocolFactory;
        }
    }

    /**
     * Accessed configured {@link Entry}s for this SPI provider.
     *
     * @return an immutable view
     */
    protected abstract Set<Entry> entries();
}
