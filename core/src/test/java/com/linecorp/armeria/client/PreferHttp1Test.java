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

package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class PreferHttp1Test {
    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.http(0);
            sb.https(0);
            sb.tlsSelfSigned();
            sb.service("/", (ctx, req) -> HttpResponse.of("Hello, world!"));
        }
    };

    @EnumSource(value = SessionProtocol.class, names = {"PROXY", "UNDEFINED"}, mode = EnumSource.Mode.EXCLUDE)
    @ParameterizedTest
    void shouldPreferHttp1(SessionProtocol protocol) throws InterruptedException {
        try (ClientFactory factory = ClientFactory.builder()
                                                  .preferHttp1(true)
                                                  .tlsNoVerify()
                                                  .build()) {
            final BlockingWebClient client = WebClient.builder(server.uri(protocol))
                                                      .factory(factory)
                                                      .build()
                                                      .blocking();
            final AggregatedHttpResponse response = client.get("/");
            assertThat(response.contentUtf8()).isEqualTo("Hello, world!");
            final RequestLog log = server.requestContextCaptor().take().log().whenComplete().join();
            switch (protocol) {
                case HTTP:
                    assertThat(log.sessionProtocol()).isEqualTo(SessionProtocol.H1C);
                    break;
                case HTTPS:
                    assertThat(log.sessionProtocol()).isEqualTo(SessionProtocol.H1);
                    break;
                default:
                    assertThat(log.sessionProtocol()).isEqualTo(protocol);
            }
        }
    }

    @Test
    void reuseH1Pool() throws InterruptedException {
        final CountingConnectionPoolListener connectionPoolListener = new CountingConnectionPoolListener();
        try (ClientFactory factory = ClientFactory.builder()
                                                  .preferHttp1(true)
                                                  .connectionPoolListener(connectionPoolListener)
                                                  .tlsNoVerify()
                                                  .build()) {
            final BlockingWebClient client = WebClient.builder()
                                                      .factory(factory)
                                                      .build()
                                                      .blocking();
            final AggregatedHttpResponse h2Response = client.get(server.uri(SessionProtocol.H2C).resolve("/")
                                                                       .toString());
            assertThat(h2Response.contentUtf8()).isEqualTo("Hello, world!");
            final RequestLog log0 = server.requestContextCaptor().take().log().whenComplete().join();
            assertThat(log0.sessionProtocol()).isEqualTo(SessionProtocol.H2C);
            assertThat(connectionPoolListener.opened()).isEqualTo(1);
            for (int i = 0; i < 3; i++) {
                final AggregatedHttpResponse h1Response = client.get(server.uri(SessionProtocol.HTTP)
                                                                           .resolve("/").toString());
                assertThat(h1Response.contentUtf8()).isEqualTo("Hello, world!");
                final RequestLog log1 = server.requestContextCaptor().take().log().whenComplete().join();
                assertThat(log1.sessionProtocol()).isEqualTo(SessionProtocol.H1C);
            }
            assertThat(connectionPoolListener.opened()).isEqualTo(2);
        }
    }
}
