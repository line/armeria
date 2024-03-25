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

import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.util.DomainSocketAddress;
import com.linecorp.armeria.common.util.SystemInfo;
import com.linecorp.armeria.internal.testing.EnabledOnOsWithDomainSockets;
import com.linecorp.armeria.internal.testing.TemporaryFolderExtension;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

@EnabledOnOsWithDomainSockets
class DomainSocketClientTest {

    private static final String ABSTRACT_PATH =
            '\0' + DomainSocketClientTest.class.getSimpleName() + '-' +
            ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);

    @RegisterExtension
    static final TemporaryFolderExtension tempDir = new TemporaryFolderExtension();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.http(domainSocketAddress(false));
            sb.https(domainSocketAddress(false));
            if (SystemInfo.isLinux()) {
                sb.http(domainSocketAddress(true));
                sb.https(domainSocketAddress(true));
            }
            sb.tlsSelfSigned();
            sb.service("/greet", (ctx, req) -> HttpResponse.builder()
                                                           .ok()
                                                           .content("Hello!")
                                                           .build());
        }
    };

    @ParameterizedTest
    @CsvSource({
            "H1C,   false",
            "H1C,   true",
            "H2C,   false",
            "H1,    false",
            "H2,    false",
            "HTTP,  false",
            "HTTPS, false"
    })
    void shouldSupportConnectingToDomainSocket(SessionProtocol protocol, boolean useAbstractNamespace) {
        if (useAbstractNamespace && !SystemInfo.isLinux()) {
            // Abstract namespace is not supported on macOS.
            return;
        }

        SessionProtocolNegotiationCache.clear();
        final String baseUri = protocol.uriText() + "://" +
                               domainSocketAddress(useAbstractNamespace).authority();

        // Connect to the domain socket server using a WebClient with baseURI and send a request to it.
        final BlockingWebClient client2 = WebClient.builder(baseUri)
                                                   .factory(ClientFactory.insecure())
                                                   .build()
                                                   .blocking();
        final AggregatedHttpResponse res2 = client2.get("/greet");
        assertThat(res2.contentUtf8()).isEqualTo("Hello!");
        assertThat(res2.status()).isEqualTo(HttpStatus.OK);

        // Connect to the domain socket server using a WebClient without baseURI and send a request to it.
        try (ClientRequestContextCaptor ctxCaptor = Clients.newContextCaptor()) {
            final BlockingWebClient client = WebClient.builder()
                                                      .factory(ClientFactory.insecure())
                                                      .build()
                                                      .blocking();
            final AggregatedHttpResponse res = client.get(baseUri + "/greet");
            assertThat(res.contentUtf8()).isEqualTo("Hello!");
            assertThat(res.status()).isEqualTo(HttpStatus.OK);

            final ClientRequestContext ctx = ctxCaptor.get();
            final String expectedAddress = domainSocketAddress(useAbstractNamespace).toString();
            assertThat(ctx.localAddress()).hasToString(expectedAddress);
            assertThat(ctx.remoteAddress()).hasToString(expectedAddress);
        }
    }

    private static DomainSocketAddress domainSocketAddress(boolean useAbstractNamespace) {
        return DomainSocketAddress.of(
                useAbstractNamespace ? ABSTRACT_PATH : tempDir.getRoot().resolve("test.sock").toString());
    }
}
