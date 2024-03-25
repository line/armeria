/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.common.thrift.text.uuid;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.apache.thrift.TException;
import org.apache.thrift.async.AsyncMethodCallback;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.thrift.ThriftClients;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.thrift.ThriftSerializationFormats;
import com.linecorp.armeria.internal.testing.GenerateNativeImageTrace;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import testing.thrift.uuid.TestUuidService;
import testing.thrift.uuid.UuidMessage;

@GenerateNativeImageTrace
class UuidMessageTest {
    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/", THttpService.of(new TestUuidServiceImpl()));
        }
    };

    @Test
    void shouldMarshallUuidType() throws TException {
        for (SerializationFormat serializationFormat : ThriftSerializationFormats.values()) {
            final TestUuidService.Iface client = ThriftClients.builder(server.httpUri())
                                                              .serializationFormat(serializationFormat)
                                                              .build(TestUuidService.Iface.class);
            final UuidMessage request = new UuidMessage(UUID.randomUUID(), "hello");
            final UuidMessage response = client.echo(request);
            assertThat(response).isEqualTo(request);
        }
    }

    private static class TestUuidServiceImpl implements TestUuidService.AsyncIface {

        @Override
        public void echo(UuidMessage request, AsyncMethodCallback<UuidMessage> resultHandler)
                throws TException {
            resultHandler.onComplete(request);
        }
    }
}
