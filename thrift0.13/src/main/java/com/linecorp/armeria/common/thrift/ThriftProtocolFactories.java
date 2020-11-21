/*
 * Copyright 2015 LINE Corporation
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

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;

import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TCompactProtocol;
import org.apache.thrift.protocol.TJSONProtocol;
import org.apache.thrift.protocol.TProtocolFactory;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.thrift.ThriftProtocolFactoryProvider.Entry;
import com.linecorp.armeria.common.thrift.text.TTextProtocolFactory;

/**
 * Provides a set of the known {@link TProtocolFactory} instances.
 */
public final class ThriftProtocolFactories {

    /**
     * {@link TProtocolFactory} for Thrift TBinary protocol.
     */
    public static final TProtocolFactory BINARY = new TBinaryProtocol.Factory() {
        private static final long serialVersionUID = -9020693963961565748L;

        @Override
        public String toString() {
            return "TProtocolFactory(binary)";
        }
    };

    /**
     * {@link TProtocolFactory} for Thrift TCompact protocol.
     */
    public static final TProtocolFactory COMPACT = new TCompactProtocol.Factory() {
        private static final long serialVersionUID = 1629726795326210377L;

        @Override
        public String toString() {
            return "TProtocolFactory(compact)";
        }
    };

    /**
     * {@link TProtocolFactory} for the Thrift TJSON protocol.
     */
    public static final TProtocolFactory JSON = new TJSONProtocol.Factory() {
        private static final long serialVersionUID = 7690636602996870153L;

        @Override
        public String toString() {
            return "TProtocolFactory(JSON)";
        }
    };

    /**
     * {@link TProtocolFactory} for the Thrift TText protocol.
     */
    public static final TProtocolFactory TEXT = TTextProtocolFactory.get();

    /**
     * {@link TProtocolFactory} for the Thrift TText protocol with named enums.
     */
    public static final TProtocolFactory TEXT_NAMED_ENUM = TTextProtocolFactory.get(true);

    /**
     * A way to lookup the related {@link TProtocolFactory} from a {@link SerializationFormat}.
     * Entries are provided via registered SPI {@link ThriftProtocolFactoryProvider} implementations.
     */
    private static final Map<SerializationFormat, TProtocolFactory> knownProtocolFactories;

    static {
        final List<ThriftProtocolFactoryProvider> providers = ImmutableList.copyOf(
                ServiceLoader.load(ThriftProtocolFactoryProvider.class,
                                   ThriftProtocolFactoryProvider.class.getClassLoader()));
        knownProtocolFactories = providers
                .stream()
                .map(ThriftProtocolFactoryProvider::entries)
                .flatMap(Set::stream)
                .collect(toImmutableMap(Entry::getSerializationFormat,
                                        Entry::getTProtocolFactory));
    }

    /**
     * Returns the {@link TProtocolFactory} for the specified {@link SerializationFormat}.
     *
     * @throws IllegalArgumentException if the specified {@link SerializationFormat} is not a
     *         known Thrift serialization format
     */
    public static TProtocolFactory get(SerializationFormat serializationFormat) {
        requireNonNull(serializationFormat, "serializationFormat");
        return Optional.ofNullable(knownProtocolFactories.get(serializationFormat))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unsupported Thrift serializationFormat: " + serializationFormat));
    }

    /**
     * Retrieves all registered Thrift serialization formats.
     *
     * @return an view of the registered Thrift serialization formats.
     */
    public static Set<SerializationFormat> getThriftSerializationFormats() {
        return knownProtocolFactories.keySet();
    }

    private ThriftProtocolFactories() {}
}
