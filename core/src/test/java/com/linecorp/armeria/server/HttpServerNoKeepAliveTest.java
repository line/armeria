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

package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.EnumSource.Mode;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.CountingConnectionPoolListener;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class HttpServerNoKeepAliveTest {
    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.idleTimeoutMillis(0);
            sb.http(0);
            sb.https(0);
            sb.tlsSelfSigned();
            sb.service("/", (ctx, req) -> {
                return HttpResponse.builder()
                                   .ok()
                                   .content("OK")
                                   .header(HttpHeaderNames.CONNECTION, "close")
                                   .build();
            });
        }
    };

    @EnumSource(value = SessionProtocol.class, mode = Mode.EXCLUDE, names = {"PROXY", "UNDEFINED"})
    @ParameterizedTest
    void shouldDisconnectWhenConnectionCloseIsIncluded(SessionProtocol protocol) {
        final CountingConnectionPoolListener poolListener = new CountingConnectionPoolListener();
        try (ClientFactory factory = ClientFactory.builder()
                                                  .idleTimeoutMillis(0)
                                                  .tlsNoVerify()
                                                  .connectionPoolListener(poolListener)
                                                  .build()) {
            final BlockingWebClient client = WebClient.builder(server.uri(protocol))
                                                      .factory(factory)
                                                      .build()
                                                      .blocking();
            final AggregatedHttpResponse response = client.get("/");
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.contentUtf8()).isEqualTo("OK");
            await().untilAsserted(() -> {
                assertThat(poolListener.opened()).isEqualTo(1);
                assertThat(poolListener.closed()).isEqualTo(1);
            });
        }
    }
}
