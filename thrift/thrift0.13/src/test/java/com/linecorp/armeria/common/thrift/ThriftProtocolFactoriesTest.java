/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;

import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TCompactProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TProtocolFactory;
import org.apache.thrift.transport.TMemoryBuffer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.linecorp.armeria.internal.common.thrift.TByteBufTransport;
import com.linecorp.armeria.internal.server.thrift.ThriftVersionDetector;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

class ThriftProtocolFactoriesTest {

    @ParameterizedTest
    @MethodSource("protocolFactories")
    void otherTransports(TProtocolFactory factory, Class<? extends TProtocol> protocolType) throws Exception {
        final TProtocol protocol = factory.getProtocol(new TMemoryBuffer(1));
        assertThat(protocol).isExactlyInstanceOf(protocolType);
    }

    @ParameterizedTest
    @MethodSource("protocolFactories")
    void byteBufTransport(TProtocolFactory factory, Class<? extends TProtocol> protocolType) {
        final ByteBuf buf = Unpooled.buffer();

        try {
            final TProtocol protocol = factory.getProtocol(new TByteBufTransport(buf));

            if (ThriftVersionDetector.minorVersion() >= 24) {
                assertThat(protocol).isExactlyInstanceOf(protocolType);
            } else {
                assertThat(protocol).isInstanceOf(protocolType).isNotExactlyInstanceOf(protocolType);
            }
        } finally {
            buf.release();
        }
    }

    private static Stream<Arguments> protocolFactories() {
        return Stream.of(
                Arguments.of(ThriftProtocolFactories.binary(100, 100), TBinaryProtocol.class),
                Arguments.of(ThriftProtocolFactories.compact(100, 100), TCompactProtocol.class));
    }
}
