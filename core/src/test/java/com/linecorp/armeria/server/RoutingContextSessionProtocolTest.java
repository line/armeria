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

package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.testing.server.ServiceRequestContextCaptor;

class RoutingContextSessionProtocolTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.http(0);
            sb.https(0);
            sb.tlsSelfSigned();
            sb.service("/", (ctx, req) -> HttpResponse.of(200));
        }
    };

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = { "H1C", "H1", "H2C", "H2" })
    void routingContextSessionProtocol(SessionProtocol sessionProtocol) throws InterruptedException {
        final BlockingWebClient blockingClient = WebClient.builder(server.uri(sessionProtocol))
                                                          .factory(ClientFactory.insecure())
                                                          .build()
                                                          .blocking();
        assertSessionProtocol(blockingClient, sessionProtocol);
    }

    @Test
    void upgradeRequest() throws InterruptedException {
        try (ClientFactory factory = ClientFactory.builder().useHttp2Preface(false).build()) {
            final BlockingWebClient blockingClient =
                    WebClient.builder(server.httpUri())
                             .factory(factory)
                             .build()
                             .blocking();
            assertSessionProtocol(blockingClient, SessionProtocol.H2C);
        }
    }

    private static void assertSessionProtocol(
            BlockingWebClient blockingClient, SessionProtocol sessionProtocol) throws InterruptedException {
        assertThat(blockingClient.get("/").status()).isSameAs(HttpStatus.OK);
        final ServiceRequestContextCaptor captor = server.requestContextCaptor();
        assertThat(captor.poll().routingContext().sessionProtocol()).isSameAs(sessionProtocol);
    }
}
