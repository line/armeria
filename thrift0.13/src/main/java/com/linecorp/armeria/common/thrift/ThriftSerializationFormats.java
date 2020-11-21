/*
 *  Copyright 2017 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */
package com.linecorp.armeria.common.thrift;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;

import org.apache.thrift.protocol.TProtocolFactory;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.thrift.ThriftProtocolFactoryProvider.Entry;

/**
 * Registered Thrift-related {@link SerializationFormat} instances.
 */
public final class ThriftSerializationFormats {

    /**
     * Thrift TBinary serialization format.
     */
    public static final SerializationFormat BINARY = SerializationFormat.of("tbinary");

    /**
     * Thrift TCompact serialization format.
     */
    public static final SerializationFormat COMPACT = SerializationFormat.of("tcompact");

    /**
     * Thrift TJSON serialization format.
     */
    public static final SerializationFormat JSON = SerializationFormat.of("tjson");

    /**
     * Thrift TText serialization format. This format is not optimized for performance or backwards
     * compatibility and should only be used in non-production use cases like debugging.
     */
    public static final SerializationFormat TEXT = SerializationFormat.of("ttext");

    /**
     * Thrift TText serialization format with named enums. This format is not optimized for performance
     * or backwards compatibility and should only be used in non-production use cases like debugging.
     */
    public static final SerializationFormat TEXT_NAMED_ENUM = SerializationFormat.of("ttext-named-enum");

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
    public static Set<SerializationFormat> values() {
        return knownProtocolFactories.keySet();
    }

    /**
     * Returns whether the specified {@link SerializationFormat} is Thrift.
     */
    public static boolean isThrift(SerializationFormat format) {
        return values().contains(requireNonNull(format, "format"));
    }

    private ThriftSerializationFormats() {}
}
