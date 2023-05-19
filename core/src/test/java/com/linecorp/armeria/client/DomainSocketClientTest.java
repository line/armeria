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

import java.nio.file.Path;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.util.DomainSocketAddress;
import com.linecorp.armeria.internal.testing.EnabledIfSupportsDomainSocket;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

@EnabledIfSupportsDomainSocket
class DomainSocketClientTest {

    @TempDir
    static Path tempDir;

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.http(domainSocketAddress());
            sb.https(domainSocketAddress());
            sb.tlsSelfSigned();
            sb.service("/greet", (ctx, req) -> HttpResponse.builder()
                                                           .ok()
                                                           .content("Hello!")
                                                           .build());
        }
    };

    @ParameterizedTest
    @CsvSource({
            "H1C", "H2C", "H1", "H2", "HTTP", "HTTPS"
    })
    void shouldSupportConnectingToDomainSocket(SessionProtocol protocol) {
        SessionProtocolNegotiationCache.clear();
        final String baseUri = protocol.uriText() + "://" + domainSocketAddress().authority();

        // Connect to the domain socket server using a WebClient with baseURI and send a request to it.
        final BlockingWebClient client2 = WebClient.builder(baseUri)
                                                   .factory(ClientFactory.insecure())
                                                   .build()
                                                   .blocking();
        final AggregatedHttpResponse res2 = client2.get("/greet");
        assertThat(res2.contentUtf8()).isEqualTo("Hello!");
        assertThat(res2.status()).isEqualTo(HttpStatus.OK);

        // Connect to the domain socket server using a WebClient without baseURI and send a request to it.
        final BlockingWebClient client = WebClient.builder()
                                                  .factory(ClientFactory.insecure())
                                                  .build()
                                                  .blocking();
        final AggregatedHttpResponse res = client.get(baseUri + "/greet");
        assertThat(res.contentUtf8()).isEqualTo("Hello!");
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
    }

    private static DomainSocketAddress domainSocketAddress() {
        return DomainSocketAddress.of(tempDir.resolve("test.sock"));
    }
}
