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
import static com.linecorp.armeria.common.thrift.ThriftProtocolFactories.IS_THRIFT_091;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;

import org.apache.thrift.protocol.TProtocolFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;

import com.linecorp.armeria.common.SerializationFormat;

/**
 * Provides Thrift-related {@link SerializationFormat} instances and their {@link TProtocolFactory}s.
 */
public final class ThriftSerializationFormats {

    private static final Logger logger = LoggerFactory.getLogger(ThriftSerializationFormats.class);

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

    private static final List<ThriftProtocolFactoryProvider> protocolFactoryProviders;
    private static final Map<SerializationFormat, TProtocolFactory> lengthUnlimitedProtocolFactories;
    private static final Set<SerializationFormat> knownSerializationFormats;

    static {
        protocolFactoryProviders = ImmutableList.copyOf(
                ServiceLoader.load(ThriftProtocolFactoryProvider.class,
                                   ThriftProtocolFactoryProvider.class.getClassLoader()));

        lengthUnlimitedProtocolFactories = protocolFactoryProviders
                .stream()
                .flatMap(provider -> provider.serializationFormats().stream().map(format -> {
                    final TProtocolFactory factory = provider.protocolFactory(format, 0, 0);
                    return factory != null ? Maps.immutableEntry(format, factory) : null;
                }))
                .filter(Objects::nonNull)
                .collect(toImmutableMap(Entry::getKey, Entry::getValue));

        knownSerializationFormats = lengthUnlimitedProtocolFactories.keySet();
    }

    /**
     * Returns the {@link TProtocolFactory} for the specified {@link SerializationFormat}.
     *
     * @throws IllegalArgumentException if the specified {@link SerializationFormat} is not a
     *                                  known Thrift serialization format
     * @deprecated Use {@link #protocolFactory(SerializationFormat, int, int)} instead.
     */
    @Deprecated
    public static TProtocolFactory protocolFactory(SerializationFormat serializationFormat) {
        return protocolFactory(serializationFormat, 0, 0);
    }

    /**
     * Returns the {@link TProtocolFactory} for the specified {@link SerializationFormat},
     * {@code maxStringLength} and {@code maxContainerLength}.
     *
     * <p>Note that the {@code maxStringLength} and {@code maxContainerLength} is ignored if the
     * {@link TProtocolFactory} does not support length limit.
     *
     * @param serializationFormat the serialization that {@link TProtocolFactory} supports.
     * @param maxStringLength the maximum allowed number of bytes to read from the transport for
     *                        variable-length fields (such as strings or binary). {@code 0} means unlimited.
     * @param maxContainerLength the maximum allowed number of containers to read from the transport for
     *                           maps, sets and lists. {@code 0} means unlimited.
     * @throws IllegalArgumentException if the specified {@link SerializationFormat} is not a
     *                                  known Thrift serialization format
     */
    public static TProtocolFactory protocolFactory(SerializationFormat serializationFormat, int maxStringLength,
                                                   int maxContainerLength) {
        requireNonNull(serializationFormat, "serializationFormat");
        if (maxStringLength == 0 && maxContainerLength == 0) {
            final TProtocolFactory protocolFactory = lengthUnlimitedProtocolFactories.get(serializationFormat);
            if (protocolFactory != null) {
                return protocolFactory;
            } else {
                throw newUnsupportedFormatException(serializationFormat);
            }
        }

        if (IS_THRIFT_091 && (serializationFormat == BINARY || serializationFormat == COMPACT)) {
            logger.warn("Thrift 0.9.1 does not support length limit for {}. " +
                        "Ignoring maxStringLength({}) and maxContainerLength({}).",
                        serializationFormat, maxStringLength, maxContainerLength);
        }

        for (ThriftProtocolFactoryProvider provider : protocolFactoryProviders) {
            final TProtocolFactory protocolFactory =
                    provider.protocolFactory(serializationFormat, maxStringLength, maxContainerLength);
            if (protocolFactory != null) {
                return protocolFactory;
            }
        }
        throw newUnsupportedFormatException(serializationFormat);
    }

    private static IllegalArgumentException newUnsupportedFormatException(
            SerializationFormat serializationFormat) {
        return new IllegalArgumentException("Unsupported Thrift serializationFormat: " + serializationFormat);
    }

    /**
     * Retrieves all registered Thrift serialization formats.
     *
     * @return an view of the registered Thrift serialization formats.
     */
    public static Set<SerializationFormat> values() {
        return knownSerializationFormats;
    }

    /**
     * Returns whether the specified {@link SerializationFormat} is Thrift.
     */
    public static boolean isThrift(SerializationFormat format) {
        return values().contains(requireNonNull(format, "format"));
    }

    private ThriftSerializationFormats() {}
}
