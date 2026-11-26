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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
        final ServerBuilder serverBuilder = Server.builder().http(18080);
        final VirtualHostBuilder virtualHost1 = serverBuilder.virtualHost("foo.com");
        virtualHost1.service("/foo", (ctx, req) -> HttpResponse.of("foo"));
        final VirtualHostBuilder virtualHost2 = serverBuilder.virtualHost("foo.com");
        final VirtualHostBuilder virtualHost3 = serverBuilder.virtualHost("foo.com:18080");
        virtualHost3.service("/foo", (ctx, req) -> HttpResponse.of("foo with port"));
        final VirtualHostBuilder virtualHost4 = serverBuilder.virtualHost("bar.com");
        virtualHost4.service("/bar", (ctx, req) -> HttpResponse.of("bar"));

        assertThat(virtualHost1).isSameAs(virtualHost2);
        assertThat(virtualHost2).isNotSameAs(virtualHost3);
        assertThat(virtualHost2).isNotSameAs(virtualHost4);

        final ServerConfig config = serverBuilder.build().config();
        assertThat(config.virtualHosts()).extracting(VirtualHost::hostnamePattern)
                                         .containsExactly("foo.com", "foo.com:18080", "bar.com", "*");
        assertThat(config.serviceConfigs()).hasSize(3);
    }

    @Test
    void shouldReturnSameInstanceForHostnameBasedVirtualHostWithPort() {
        final ServerBuilder serverBuilder = Server.builder().http(18080).http(18081);
        final VirtualHostBuilder virtualHost1 = serverBuilder.virtualHost("foo.com:18080");
        virtualHost1.service("/foo", (ctx, req) -> HttpResponse.of("foo"));
        final VirtualHostBuilder virtualHost2 = serverBuilder.virtualHost("foo.com:18080");
        final VirtualHostBuilder virtualHost3 = serverBuilder.virtualHost("foo.com:18081");
        virtualHost3.service("/bar", (ctx, req) -> HttpResponse.of("bar"));

        assertThat(virtualHost1).isSameAs(virtualHost2);
        assertThat(virtualHost2).isNotSameAs(virtualHost3);

        final ServerConfig config = serverBuilder.build().config();
        assertThat(config.virtualHosts()).extracting(VirtualHost::hostnamePattern)
                                         .containsExactly("foo.com:18080", "foo.com:18081", "*");
        assertThat(config.serviceConfigs()).hasSize(2);
    }

    @ParameterizedTest
    @CsvSource({
            "false, false, false",
            "true, true, true",
            "false, true, false",
            "true, false, true"
    })
    void registersHostnameBasedVirtualHostOnlyOnce(boolean firstCustomizer,
                                                   boolean secondCustomizer,
                                                   boolean thirdCustomizer) {
        final ServerBuilder serverBuilder = Server.builder();
        final boolean[] useCustomizer = { firstCustomizer, secondCustomizer, thirdCustomizer };
        for (int i = 0; i < useCustomizer.length; i++) {
            final String hostnamePattern = i == 1 ? "FOO.COM" : "foo.com";
            final String path = "/service" + i;
            final ServerBuilder returnedServerBuilder;
            if (useCustomizer[i]) {
                returnedServerBuilder = serverBuilder.withVirtualHost(hostnamePattern, builder -> {
                    builder.service(path, (ctx, req) -> HttpResponse.of(path));
                });
            } else {
                final VirtualHostBuilder virtualHostBuilder = serverBuilder.virtualHost(hostnamePattern);
                virtualHostBuilder.service(path, (ctx, req) -> HttpResponse.of(path));
                returnedServerBuilder = virtualHostBuilder.and();
            }
            assertThat(returnedServerBuilder).isSameAs(serverBuilder);
        }

        final ServerConfig config = serverBuilder.build().config();
        assertThat(config.virtualHosts()).extracting(VirtualHost::hostnamePattern)
                                         .containsExactly("foo.com", "*");
        assertThat(config.virtualHosts().get(0).serviceConfigs())
                .extracting(serviceConfig -> serviceConfig.route().patternString())
                .containsExactly("/service0", "/service1", "/service2");
        assertThat(config.serviceConfigs()).hasSize(3);
    }
}
