/*
 * Copyright 2025 LINE Corporation
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

package com.linecorp.armeria.client.thrift;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.util.stream.Stream;

import org.apache.thrift.TException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.linecorp.armeria.client.ClientBuilderParams;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.RpcPreprocessor;
import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.thrift.ThriftSerializationFormats;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import testing.thrift.main.HelloService;

class ThriftClientTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/hello", THttpService.of((HelloService.AsyncIface) (name, resultHandler)
                    -> resultHandler.onComplete("Hello, " + name + '!')));
            sb.service("/", THttpService.of((HelloService.AsyncIface) (name, resultHandler)
                    -> resultHandler.onComplete(name)));
            sb.service("/compact", THttpService.builder()
                                               .defaultSerializationFormat(ThriftSerializationFormats.COMPACT)
                                               .addService((HelloService.AsyncIface) (name, resultHandler)
                                                       -> resultHandler.onComplete("Compact " + name))
                                               .build());
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

    private static Stream<Arguments> preprocessors_args() {
        final RpcPreprocessor rpcPreprocessor =
                RpcPreprocessor.of(SessionProtocol.HTTP, server.httpEndpoint());
        return Stream.of(
                Arguments.of(ThriftClients.builder(rpcPreprocessor)
                                          .build(HelloService.Iface.class), "Armeria"),
                Arguments.of(ThriftClients.newClient(rpcPreprocessor, HelloService.Iface.class), "Armeria"),
                Arguments.of(ThriftClients.newClient(
                        ThriftSerializationFormats.COMPACT,
                        rpcPreprocessor, HelloService.Iface.class, "/compact"), "Compact Armeria"),
                Arguments.of(ThriftClients.builder(rpcPreprocessor)
                                          .path("/hello")
                                          .build(HelloService.Iface.class), "Hello, Armeria!"),
                Arguments.of(ThriftClients.newClient(ThriftSerializationFormats.BINARY,
                                                     rpcPreprocessor, HelloService.Iface.class, "/hello"),
                             "Hello, Armeria!")
        );
    }

    @ParameterizedTest
    @MethodSource("preprocessors_args")
    void preprocessors(HelloService.Iface iface, String expected) throws Exception {
        final ClientBuilderParams params = (ClientBuilderParams) Proxy.getInvocationHandler(iface);
        assertThat(iface.hello("Armeria")).isEqualTo(expected);

        final HelloService.Iface derived = Clients.newDerivedClient(
                iface, ClientOptions.WRITE_TIMEOUT_MILLIS.newValue(Long.MAX_VALUE));
        assertThat(derived.hello("Armeria")).isEqualTo(expected);
        final ClientBuilderParams derivedParams = (ClientBuilderParams) Proxy.getInvocationHandler(derived);

        assertThat(params.options().clientPreprocessors())
                .isEqualTo(derivedParams.options().clientPreprocessors());
    }
}
