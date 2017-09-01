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

import static java.util.Objects.requireNonNull;

import java.util.Set;

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.SerializationFormat;

/**
 * Thrift-related {@link SerializationFormat} instances.
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

    private static final Set<SerializationFormat> THRIFT_FORMATS =
            ImmutableSet.of(BINARY, COMPACT, JSON, TEXT);

    /**
     * Returns the set of all known Thrift serialization formats.
     */
    public static Set<SerializationFormat> values() {
        return THRIFT_FORMATS;
    }

    /**
     * Returns whether the specified {@link SerializationFormat} is Thrift.
     */
    public static boolean isThrift(SerializationFormat format) {
        return values().contains(requireNonNull(format, "format"));
    }

    private ThriftSerializationFormats() {}
}
