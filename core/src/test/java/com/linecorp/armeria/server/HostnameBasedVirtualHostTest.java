/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ServerSocket;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.internal.testing.MockAddressResolverGroup;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class HostnameBasedVirtualHostTest {

    private static int fooHostPort;

    @RegisterExtension
    static ServerExtension serverWithPortMapping = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {

            try (ServerSocket ss = new ServerSocket(0)) {
                fooHostPort = ss.getLocalPort();
            }

            sb.http(fooHostPort)
              .virtualHost("foo.com:" + fooHostPort)
              .service("/foo", (ctx, req) -> HttpResponse.of("foo with port"))
              .and()
              .virtualHost("foo.com:" + fooHostPort)
              .service("/bar", (ctx, req) -> HttpResponse.of("bar with port"))
              .and()
              .virtualHost("foo.bar.com")
              .service("/foo-bar", (ctx, req) -> HttpResponse.of("foo bar"))
              .and()
              .build();
        }
    };

    @Test
    void testHostnamePattern() {
        try (ClientFactory factory = ClientFactory.builder()
                                                  .addressResolverGroupFactory(
                                                          unused -> MockAddressResolverGroup.localhost())
                                                  .build()) {

            final WebClient client = WebClient.builder("http://foo.com:" + fooHostPort)
                                              .factory(factory)
                                              .build();
            AggregatedHttpResponse response = client.get("/foo").aggregate().join();
            assertThat(response.contentUtf8()).isEqualTo("foo with port");

            response = client.get("/bar").aggregate().join();
            assertThat(response.contentUtf8()).isEqualTo("bar with port");
        }
    }

    @Test
    void shouldReturnSameInstanceForHostnameBasedVirtualHost() {
        final ServerBuilder serverBuilder = Server.builder();
        final VirtualHostBuilder virtualHost1 = serverBuilder.virtualHost("foo.com");
        final VirtualHostBuilder virtualHost2 = serverBuilder.virtualHost("foo.com");
        assertThat(virtualHost1).isSameAs(virtualHost2);
        final VirtualHostBuilder virtualHost3 = serverBuilder.virtualHost("foo.com:18080");
        assertThat(virtualHost2).isNotSameAs(virtualHost3);
        final VirtualHostBuilder virtualHost4 = serverBuilder.virtualHost("bar.com");
        assertThat(virtualHost2).isNotSameAs(virtualHost4);
    }

    @Test
    void shouldReturnSameInstanceForHostnameBasedVirtualHostWithPort() {
        final ServerBuilder serverBuilder = Server.builder();
        final VirtualHostBuilder virtualHost1 = serverBuilder.virtualHost("foo.com:18080");
        final VirtualHostBuilder virtualHost2 = serverBuilder.virtualHost("foo.com:18080");
        assertThat(virtualHost1).isSameAs(virtualHost2);
        final VirtualHostBuilder virtualHost3 = serverBuilder.virtualHost("foo.com:18081");
        assertThat(virtualHost2).isNotSameAs(virtualHost3);
    }
}
