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

package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.internal.testing.MockAddressResolverGroup;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class UnderscoredAuthorityTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/", (ctx, req) -> HttpResponse.of("Hello, world!"));
        }
    };

    @Test
    void shouldAllowUnderscoreInAuthority() {
        try (ClientFactory factory = ClientFactory.builder()
                                                  .addressResolverGroupFactory(unused -> {
                                                      return MockAddressResolverGroup.localhost();
                                                  })
                                                  .build()) {
            final BlockingWebClient client =
                    WebClient.builder("http://my_key.z1.armeria.dev:" + server.httpPort())
                             .factory(factory)
                             .build()
                             .blocking();

            try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
                final AggregatedHttpResponse response = client.get("/");
                final ClientRequestContext ctx = captor.get();
                assertThat(response.status()).isEqualTo(HttpStatus.OK);
                assertThat(response.contentUtf8()).isEqualTo("Hello, world!");
                final RequestLog log = ctx.log().whenComplete().join();

                assertThat(log.requestHeaders().authority())
                        .isEqualTo("my_key.z1.armeria.dev:" + server.httpPort());
            }
        }
    }

    @Test
    void shouldAllowUnderscoreInEndpoint() {
        final Endpoint endpoint = Endpoint.parse("my_key.z1.armeria.dev:" + server.httpPort());
        assertThat(endpoint.authority()).isEqualTo("my_key.z1.armeria.dev:" + server.httpPort());
        assertThat(endpoint.host()).isEqualTo("my_key.z1.armeria.dev");
        assertThat(endpoint.port()).isEqualTo(server.httpPort());
    }
}
