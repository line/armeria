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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TCompactProtocol;
import org.apache.thrift.protocol.TJSONProtocol;
import org.apache.thrift.protocol.TProtocolFactory;

import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.thrift.text.TTextProtocolFactory;
import com.linecorp.armeria.internal.common.thrift.DefaultThriftProtocolFactoryProvider;

/**
 * Provides a set of well-known {@link TProtocolFactory}s.
 */
public final class ThriftProtocolFactories {

    /**
     * {@link TProtocolFactory} for Thrift TBinary protocol.
     *
     * <p>Note that this Thrift TBinary protocol does not limit the maximum number of bytes to read from the
     * transport. Therefore, it is recommended NOT to use this factory in a public network. If an attacker
     * sends a header with a large message size, an `OutOfMemoryError` may occur.
     * Related: <a href="https://issues.apache.org/jira/browse/THRIFT-2572">Add string/collection length limit
     * checks (from C++) to java protocol readers</a>
     *
     * @deprecated Use {@link #binary(int, int)} instead.
     */
    @Deprecated
    public static final TProtocolFactory BINARY = binary(0, 0);

    /**
     * {@link TProtocolFactory} for Thrift TCompact protocol.
     *
     * <p>Note that this Thrift TCompact protocol does not limit the maximum number of bytes to read from the
     * transport. Therefore, it is recommended to NOT use this factory in a public network. If an attacker
     * sends a header with a large message size, an `OutOfMemoryError` may occur.
     * Related: <a href="https://issues.apache.org/jira/browse/THRIFT-2572">Add string/collection length limit
     * checks (from C++) to java protocol readers</a>
     *
     * @deprecated Use {@link #compat(int, int)} instead.
     */
    @Deprecated
    public static final TProtocolFactory COMPACT = compat(0, 0);

    /**
     * {@link TProtocolFactory} for the Thrift TJSON protocol.
     *
     * @deprecated Use {@link #json()} instead.
     */
    @Deprecated
    public static final TProtocolFactory JSON = new TJSONProtocol.Factory() {
        private static final long serialVersionUID = 7690636602996870153L;

        @Override
        public String toString() {
            return "TProtocolFactory(JSON)";
        }
    };

    /**
     * {@link TProtocolFactory} for the Thrift TText protocol.
     *
     * @deprecated Use {@link #text()} instead.
     */
    @Deprecated
    public static final TProtocolFactory TEXT = TTextProtocolFactory.get();

    /**
     * {@link TProtocolFactory} for the Thrift TText protocol with named enums.
     *
     * @deprecated Use {@link #textNamedEnum()} instead.
     */
    @Deprecated
    public static final TProtocolFactory TEXT_NAMED_ENUM = TTextProtocolFactory.get(true);

    /**
     * Alias for {@link ThriftSerializationFormats#protocolFactory(SerializationFormat)}.
     *
     * @param serializationFormat a known serialization format
     * @return the protocol factory linked to the input serializationFormat
     * @deprecated Use {@link ThriftSerializationFormats#protocolFactory(SerializationFormat)}.
     */
    @Deprecated
    public static TProtocolFactory get(SerializationFormat serializationFormat) {
        return ThriftSerializationFormats.protocolFactory(serializationFormat);
    }

    /**
     * Returns a {@link TProtocolFactory} for Thrift TBinary protocol.
     *
     * @param maxStringLength the maximum allowed number of bytes to read from the transport for
     *                        variable-length fields (such as strings or binary). {@code 0} means unlimited.
     * @param maxContainerLength the maximum allowed number of bytes to read from the transport for
     *                           containers (maps, sets, lists). {@code 0} means unlimited.
     */
    public static TProtocolFactory binary(int maxStringLength, int maxContainerLength) {
        checkArgument(maxStringLength >= 0, "maxStringLength: %s (expected: >= 0)", maxStringLength);
        checkArgument(maxContainerLength >= 0, "maxContainerLength: %s (expected: >= 0)", maxContainerLength);
        final int maxStringLength0 = maxStringLength == 0 ? -1 : maxStringLength;
        final int maxContainerLength0 = maxContainerLength == 0 ? -1 : maxContainerLength;

        return new TBinaryProtocol.Factory(false, true, maxStringLength0, maxContainerLength0) {
            private static final long serialVersionUID = -9020693963961565748L;

            @Override
            public String toString() {
                return "TProtocolFactory(binary, maxStringLength:" + maxStringLength +
                       ", maxContainerLength: " + maxContainerLength + ')';
            }
        };
    }

    /**
     * Returns a {@link TProtocolFactory} for Thrift TCompact protocol.
     *
     * @param maxStringLength the maximum allowed number of bytes to read from the transport for
     *                        variable-length fields (such as strings or binary). {@code 0} means unlimited.
     * @param maxContainerLength the maximum allowed number of bytes to read from the transport for
     *                           containers (maps, sets, lists). {@code 0} means unlimited.
     */
    public static TProtocolFactory compat(int maxStringLength, int maxContainerLength) {
        checkArgument(maxStringLength >= 0, "maxStringLength: %s (expected: >= 0)", maxStringLength);
        checkArgument(maxContainerLength >= 0, "maxContainerLength: %s (expected: >= 0)", maxContainerLength);
        final int maxStringLength0 = maxStringLength == 0 ? -1 : maxStringLength;
        final int maxContainerLength0 = maxContainerLength == 0 ? -1 : maxContainerLength;

        return new TCompactProtocol.Factory(maxStringLength0, maxContainerLength0) {
            private static final long serialVersionUID = 1629726795326210377L;

            @Override
            public String toString() {
                return "TProtocolFactory(compact, maxStringLength:" + maxStringLength +
                       ", maxContainerLength: " + maxContainerLength + ')';
            }
        };
    }

    /**
     * Returns a {@link TProtocolFactory} for the Thrift TJSON protocol.
     */
    public static TProtocolFactory json() {
        return JSON;
    }

    /**
     * Returns a {@link TProtocolFactory} for the Thrift TText protocol.
     */
    public static TProtocolFactory text() {
        return TEXT;
    }

    /**
     * Returns a {@link TProtocolFactory} for the Thrift TText protocol with named enums.
     */
    public static TProtocolFactory textNamedEnum() {
        return TEXT_NAMED_ENUM;
    }

    /**
     * Returns the {@link SerializationFormat} for the specified {@link TProtocolFactory},
     * as if it were registered by {@link DefaultThriftProtocolFactoryProvider}.
     * Consider having your own {@link TProtocolFactory} to {@link SerializationFormat} mapping if
     * necessary.
     *
     * @throws IllegalArgumentException if the specified {@link TProtocolFactory} did not match anything
     * @deprecated This method has been deprecated without a replacement since it cannot reliably work
     *             with custom protocol factories.
     */
    @Deprecated
    public static SerializationFormat toSerializationFormat(TProtocolFactory protoFactory) {
        requireNonNull(protoFactory, "protoFactory");
        if (protoFactory instanceof TBinaryProtocol.Factory) {
            return ThriftSerializationFormats.BINARY;
        } else if (protoFactory instanceof TCompactProtocol.Factory) {
            return ThriftSerializationFormats.COMPACT;
        } else if (protoFactory instanceof TJSONProtocol.Factory) {
            return ThriftSerializationFormats.JSON;
        } else if (protoFactory instanceof TTextProtocolFactory) {
            final TTextProtocolFactory factory = (TTextProtocolFactory) protoFactory;
            return factory.usesNamedEnums() ? ThriftSerializationFormats.TEXT_NAMED_ENUM
                                            : ThriftSerializationFormats.TEXT;
        } else {
            throw new IllegalArgumentException(
                    "unsupported TProtocolFactory: " + protoFactory.getClass().getName());
        }
    }

    private ThriftProtocolFactories() {}
}
