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
package com.linecorp.armeria.internal.common.thrift;

import static java.util.Objects.requireNonNull;

import java.util.Set;

import org.apache.thrift.protocol.TProtocolFactory;

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.thrift.ThriftProtocolFactories;
import com.linecorp.armeria.common.thrift.ThriftProtocolFactoryProvider;
import com.linecorp.armeria.common.thrift.ThriftSerializationFormats;

/**
 * Default registered {@link ThriftProtocolFactoryProvider}.
 * It is not overridable but you may provide and register another implementation.
 */
public final class DefaultThriftProtocolFactoryProvider extends ThriftProtocolFactoryProvider {

    private static final Set<SerializationFormat> SERIALIZATION_FORMATS =
            ImmutableSet.of(ThriftSerializationFormats.BINARY,
                            ThriftSerializationFormats.COMPACT,
                            ThriftSerializationFormats.JSON,
                            ThriftSerializationFormats.TEXT,
                            ThriftSerializationFormats.TEXT_NAMED_ENUM);

    @Override
    protected Set<SerializationFormat> serializationFormats() {
        return SERIALIZATION_FORMATS;
    }

    @Override
    protected TProtocolFactory protocolFactory(SerializationFormat serializationFormat,
                                               int maxStringLength, int maxContainerLength) {
        requireNonNull(serializationFormat, "serializationFormat");
        if (!serializationFormats().contains(serializationFormat)) {
            return null;
        }

        if (serializationFormat == ThriftSerializationFormats.BINARY) {
            return ThriftProtocolFactories.binary(maxStringLength, maxContainerLength);
        } else if (serializationFormat == ThriftSerializationFormats.COMPACT) {
            return ThriftProtocolFactories.compact(maxStringLength, maxContainerLength);
        } else if (serializationFormat == ThriftSerializationFormats.JSON) {
            return ThriftProtocolFactories.json();
        } else if (serializationFormat == ThriftSerializationFormats.TEXT) {
            return ThriftProtocolFactories.text();
        } else if (serializationFormat == ThriftSerializationFormats.TEXT_NAMED_ENUM) {
            return ThriftProtocolFactories.textNamedEnum();
        } else {
            // Should never reach here.
            throw new Error();
        }
    }
}
