/*
 *  Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.client.thrift;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.thrift.TException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.thrift.ThriftSerializationFormats;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import testing.thrift.main.HelloService;

class ThriftClientExchangeTypeTest {
    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/hello", THttpService.of((HelloService.AsyncIface) (name, resultHandler)
                    -> resultHandler.onComplete("Hello, " + name + '!')));
        }
    };

    @Test
    void unaryExchangeType() throws TException {
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final HelloService.Iface client =
                    Clients.newClient(server.httpUri(ThriftSerializationFormats.BINARY).resolve("/hello"),
                                      HelloService.Iface.class);
            assertThat(client.hello("Armeria")).isEqualTo("Hello, Armeria!");
            final ClientRequestContext ctx = captor.get();
            assertThat(ctx.exchangeType()).isEqualTo(ExchangeType.UNARY);
        }
    }
}
