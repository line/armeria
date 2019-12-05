/*
 * Copyright 2016 LINE Corporation
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

import static com.linecorp.armeria.common.SessionProtocol.H1;
import static com.linecorp.armeria.common.SessionProtocol.H1C;
import static com.linecorp.armeria.common.SessionProtocol.H2;
import static com.linecorp.armeria.common.SessionProtocol.H2C;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.testing.junit.server.ServerExtension;

import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;

@Timeout(10000)
class HttpServerHeaderValidationTest {

    static final ClientFactory clientFactory = ClientFactory.builder().sslContextCustomizer(scb -> {
        scb.trustManager(InsecureTrustManagerFactory.INSTANCE);
    }).build();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.http(0);
            sb.https(0);
            sb.tlsSelfSigned();

            sb.route().get("/headers-custom")
              .build((ctx, req) -> {
                  final String param = new QueryStringDecoder(req.path()).parameters()
                                                                         .get("param").get(0);
                  return HttpResponse.of(
                          ResponseHeaders.of(HttpStatus.OK, "server-header", param),
                          HttpData.ofUtf8("OK"));
              });
        }
    };

    @AfterAll
    static void closeClientFactory() {
        clientFactory.close();
    }

    @ParameterizedTest
    @ArgumentsSource(WebClientProvider.class)
    void malformedHeaderValue(WebClient client) throws Exception {
        final String payloadRaw = "my-header\r\nnot-a-header: should_be_illegal";
        final String payload = URLEncoder.encode(payloadRaw, StandardCharsets.US_ASCII.name());
        final String path = "/headers-custom?param=" + payload;
        final AggregatedHttpResponse res = client.get(path).aggregate().get();
        assertThat(res.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(res.headers().get("not-a-header")).isNull();
    }

    private static class WebClientProvider implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
            return Stream.of(H1C, H1, H2C, H2)
                         .map(protocol -> Arguments.of(WebClient.of(
                                 clientFactory,
                                 protocol.uriText() + "://127.0.0.1:" +
                                 (protocol.isTls() ? server.httpsPort() : server.httpPort()))));
        }
    }
}
