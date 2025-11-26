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
 *
 */

package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.TlsProvider;
import com.linecorp.armeria.internal.testing.MockAddressResolverGroup;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.SelfSignedCertificateExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class TrailingDotSniTest {

    @Order(0)
    @RegisterExtension
    static SelfSignedCertificateExtension ssc = new SelfSignedCertificateExtension("example.com");

    @Order(1)
    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.https(0);
            sb.tlsProvider(TlsProvider.builder()
                                      .keyPair("example.com", ssc.tlsKeyPair())
                                      .build());
            sb.service("/", (ctx, req) -> {
                return HttpResponse.of(200);
            });
        }
    };

    @Test
    void shouldStripTrailingDotForSni() {
        try (ClientFactory factory =
                     ClientFactory.builder()
                                  .addressResolverGroupFactory(unused -> {
                                      return MockAddressResolverGroup.localhost();
                                  })
                                  .tlsCustomizer(b -> {
                                      b.trustManager(ssc.certificate());
                                  })
                                  .build()) {

            final BlockingWebClient client = WebClient.builder("https://example.com.:" + server.httpsPort())
                                                      .factory(factory)
                                                      .build()
                                                      .blocking();
            final AggregatedHttpResponse response = client.get("/");
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    void usesAuthorityHeader() {
        try (ClientFactory factory = ClientFactory.builder()
                                                  .tlsCustomizer(b -> b.trustManager(ssc.certificate()))
                                                  .build()) {

            final BlockingWebClient client = WebClient.builder(server.httpsUri())
                                                      .factory(factory)
                                                      .decorator((delegate, ctx, req) -> {
                                                          ctx.setAdditionalRequestHeader(HttpHeaderNames.AUTHORITY, "example.com");
                                                          return delegate.execute(ctx, req);
                                                      })
                                                      .build()
                                                      .blocking();
            final AggregatedHttpResponse response = client.get("/");
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
        }
    }
}
