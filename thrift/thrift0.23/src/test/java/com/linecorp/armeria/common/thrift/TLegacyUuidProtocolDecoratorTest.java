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

import java.util.UUID;

import org.apache.thrift.TException;
import org.apache.thrift.async.AsyncMethodCallback;
import org.apache.thrift.protocol.TLegacyUuidProtocolDecorator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.thrift.ThriftClients;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import testing.thrift.uuid.TestUuidService;
import testing.thrift.uuid.UuidMessage;

class TLegacyUuidProtocolDecoratorTest {

    private static final UUID LEGACY_UUID_VALUE = UUID.fromString("01234567-89ab-cdef-1020-304050607080");
    private static final UUID NEW_UUID_VALUE = UUID.fromString("10203040-5060-7080-0123-456789abcdef");

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/", THttpService.builder()
                                        .addService(new TestUuidServiceImpl())
                                        .protocolDecorator(legacyUuidProtocolDecorator())
                                        .build());
        }
    };

    @Test
    void legacyUuidProtocol() throws TException {
        final TestUuidService.Iface client =
                ThriftClients.builder(server.httpUri())
                             .protocolDecorator(legacyUuidProtocolDecorator())
                             .build(TestUuidService.Iface.class);

        final UuidMessage request = new UuidMessage(LEGACY_UUID_VALUE, LEGACY_UUID_VALUE.toString());
        assertThat(client.echo(request)).isEqualTo(request);
    }

    @Test
    void newUuidProtocol() throws TException {
        final TestUuidService.Iface client =
                ThriftClients.builder(server.httpUri())
                             .build(TestUuidService.Iface.class);

        final UuidMessage request = new UuidMessage(LEGACY_UUID_VALUE, NEW_UUID_VALUE.toString());
        assertThat(client.echo(request)).isEqualTo(request);
    }

    private static ThriftProtocolDecorator legacyUuidProtocolDecorator() {
        return ThriftProtocolDecorator.ofTProtocolDecorator(TLegacyUuidProtocolDecorator::new);
    }

    private static final class TestUuidServiceImpl implements TestUuidService.AsyncIface {

        @Override
        public void echo(UuidMessage request, AsyncMethodCallback<UuidMessage> resultHandler)
                throws TException {
            assertThat(request.getId().toString()).isEqualTo(request.getMessage());
            resultHandler.onComplete(request);
        }
    }
}
