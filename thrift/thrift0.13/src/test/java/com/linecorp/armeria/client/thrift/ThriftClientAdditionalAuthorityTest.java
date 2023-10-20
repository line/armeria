/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.client.thrift;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import testing.thrift.main.HelloService;

class ThriftClientAdditionalAuthorityTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/", THttpService.of((HelloService.AsyncIface) (name, resultHandler)
                    -> resultHandler.onComplete(ServiceRequestContext.current().request().authority())));
        }
    };

    @Test
    void shouldDeriveAuthorityFromEndpoint() throws Exception {
        final HelloService.Iface client =
                ThriftClients.newClient("http://127.0.0.1:" + server.httpPort(), HelloService.Iface.class);
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final String expectedAuthority = "127.0.0.1:" + server.httpPort();
            assertThat(client.hello("")).isEqualTo(expectedAuthority);
            assertThat(captor.get().log().whenComplete().join().requestHeaders().authority())
                    .isEqualTo(expectedAuthority);
            assertThat(captor.get().authority()).isEqualTo(expectedAuthority);
        }
    }

    @CsvSource({ "h1c, :authority", "h2c, :authority", "http, :authority",
                 "h1c, Host", "h2c, Host", "http, Host" })
    @ParameterizedTest
    void shouldRespectAuthorityInDefaultHeaders(String protocol, String headerName) throws Exception {
        final HelloService.Iface client =
                ThriftClients.builder(protocol + "://127.0.0.1:" + server.httpPort())
                             .setHeader(headerName, "foo.com")
                             .build(HelloService.Iface.class);
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            assertThat(client.hello("")).isEqualTo("foo.com");
            assertThat(captor.get().log().whenComplete().join().requestHeaders().authority())
                    .isEqualTo("foo.com");
            assertThat(captor.get().authority()).isEqualTo("foo.com");
        }
    }

    @CsvSource({ "h1c, :authority", "h2c, :authority", "http, :authority",
                 "h1c, Host", "h2c, Host", "http, Host" })
    @ParameterizedTest
    void shouldRespectAuthorityInAdditionalHeaders(String protocol, String headerName) throws Exception {
        final HelloService.Iface client =
                ThriftClients.builder(protocol + "://127.0.0.1:" + server.httpPort())
                             .setHeader(headerName, "foo.com")
                             .build(HelloService.Iface.class);
        try (SafeCloseable ignored = Clients.withHeader(headerName, "bar.com");
             ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            assertThat(client.hello("")).isEqualTo("bar.com");
            assertThat(captor.get().log().whenComplete().join().requestHeaders().authority())
                    .isEqualTo("bar.com");
            assertThat(captor.get().authority()).isEqualTo("bar.com");
        }
    }
}
