/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.common.tls;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.ClientTlsSpec;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.retry.RetryConfig;
import com.linecorp.armeria.client.retry.RetryRule;
import com.linecorp.armeria.client.retry.RetryingClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.TlsPeerVerifierFactory;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.SelfSignedCertificateExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class SessionProtocolSwitchingTest {

    @Order(0)
    @RegisterExtension
    static final SelfSignedCertificateExtension serverCert = new SelfSignedCertificateExtension();

    @Order(1)
    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.http(0);
            sb.https(0);
            sb.tls(serverCert.tlsKeyPair());
            sb.service("/", (ctx, req) -> HttpResponse.of("protocol:" + ctx.sessionProtocol()));
            sb.service("/500", (ctx, req) -> HttpResponse.of(500));
        }
    };

    private static ClientTlsSpec noVerifySpec() {
        return ClientTlsSpec.builder()
                            .verifierFactories(TlsPeerVerifierFactory.noVerify())
                            .build();
    }

    @Test
    void setClientTlsSpecSwitchesHttpToHttps() {
        final WebClient client =
                WebClient.builder(SessionProtocol.HTTP, server.httpsEndpoint())
                         .decorator((delegate, ctx, req) -> {
                             ctx.setClientTlsSpec(noVerifySpec());
                             return delegate.execute(ctx, req);
                         })
                         .build();
        final AggregatedHttpResponse res = client.blocking().get("/");
        assertThat(res.status().code()).isEqualTo(200);
        // The server always resolves to a concrete TLS protocol
        assertThat(res.contentUtf8()).satisfiesAnyOf(
                c -> assertThat(c).isEqualTo("protocol:h1"),
                c -> assertThat(c).isEqualTo("protocol:h2"));
    }

    @Test
    void setClientTlsSpecSwitchesH1cToH1() {
        final WebClient client =
                WebClient.builder(SessionProtocol.H1C, server.httpsEndpoint())
                         .decorator((delegate, ctx, req) -> {
                             ctx.setClientTlsSpec(noVerifySpec());
                             return delegate.execute(ctx, req);
                         })
                         .build();
        final AggregatedHttpResponse res = client.blocking().get("/");
        assertThat(res.status().code()).isEqualTo(200);
        assertThat(res.contentUtf8()).isEqualTo("protocol:h1");
    }

    @Test
    void setClientTlsSpecSwitchesH2cToH2() {
        final WebClient client =
                WebClient.builder(SessionProtocol.H2C, server.httpsEndpoint())
                         .decorator((delegate, ctx, req) -> {
                             ctx.setClientTlsSpec(noVerifySpec());
                             return delegate.execute(ctx, req);
                         })
                         .build();
        final AggregatedHttpResponse res = client.blocking().get("/");
        assertThat(res.status().code()).isEqualTo(200);
        assertThat(res.contentUtf8()).isEqualTo("protocol:h2");
    }

    @Test
    void clearClientTlsSpecSwitchesHttpsToHttp() {
        final WebClient client =
                WebClient.builder(SessionProtocol.HTTPS, server.httpEndpoint())
                         .decorator((delegate, ctx, req) -> {
                             ctx.clearClientTlsSpec();
                             return delegate.execute(ctx, req);
                         })
                         .build();
        final AggregatedHttpResponse res = client.blocking().get("/");
        assertThat(res.status().code()).isEqualTo(200);
        // The server always resolves to a concrete cleartext protocol
        assertThat(res.contentUtf8()).satisfiesAnyOf(
                c -> assertThat(c).isEqualTo("protocol:h2c"),
                c -> assertThat(c).isEqualTo("protocol:h1c"));
    }

    @Test
    void clearClientTlsSpecSwitchesH1ToH1c() {
        final WebClient client =
                WebClient.builder(SessionProtocol.H1, server.httpEndpoint())
                         .decorator((delegate, ctx, req) -> {
                             ctx.clearClientTlsSpec();
                             return delegate.execute(ctx, req);
                         })
                         .build();
        final AggregatedHttpResponse res = client.blocking().get("/");
        assertThat(res.status().code()).isEqualTo(200);
        assertThat(res.contentUtf8()).isEqualTo("protocol:h1c");
    }

    @Test
    void clearClientTlsSpecSwitchesH2ToH2c() {
        final WebClient client =
                WebClient.builder(SessionProtocol.H2, server.httpEndpoint())
                         .decorator((delegate, ctx, req) -> {
                             ctx.clearClientTlsSpec();
                             return delegate.execute(ctx, req);
                         })
                         .build();
        final AggregatedHttpResponse res = client.blocking().get("/");
        assertThat(res.status().code()).isEqualTo(200);
        assertThat(res.contentUtf8()).isEqualTo("protocol:h2c");
    }

    @Test
    void requestOptionsClientTlsSpecIgnoredForHttp() {
        final WebClient client = WebClient.of(SessionProtocol.HTTP, server.httpEndpoint());
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final AggregatedHttpResponse res = client.blocking()
                                                     .prepare()
                                                     .clientTlsSpec(noVerifySpec())
                                                     .get("/")
                                                     .execute();
            assertThat(res.status().code()).isEqualTo(200);
            // clientTlsSpec should be filtered out for non-TLS protocol
            assertThat(captor.get().clientTlsSpec()).isNull();
        }
    }

    @Test
    void requestOptionsClientTlsSpecUsedForHttps() {
        // ALPN must be set explicitly for RequestOptions path (no auto-fill like setClientTlsSpec)
        final ClientTlsSpec spec = ClientTlsSpec.builder()
                                                .verifierFactories(TlsPeerVerifierFactory.noVerify())
                                                .alpnProtocols(SessionProtocol.HTTPS)
                                                .build();
        final WebClient client = WebClient.of(SessionProtocol.HTTPS, server.httpsEndpoint());
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final AggregatedHttpResponse res = client.blocking()
                                                     .prepare()
                                                     .clientTlsSpec(spec)
                                                     .get("/")
                                                     .execute();
            assertThat(res.status().code()).isEqualTo(200);
            assertThat(captor.get().clientTlsSpec()).isSameAs(spec);
        }
    }

    @Test
    void derivedCtxRetriesPreserveTlsSpec() {
        final ClientTlsSpec spec = noVerifySpec();
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final WebClient client =
                    WebClient.builder(SessionProtocol.HTTP, server.httpsEndpoint())
                             .decorator(RetryingClient.newDecorator(
                                     RetryConfig.builder(RetryRule.onServerErrorStatus())
                                                .maxTotalAttempts(3)
                                                .build()))
                             .decorator((delegate, ctx, req) -> {
                                 ctx.setClientTlsSpec(spec);
                                 return delegate.execute(ctx, req);
                             })
                             .build();
            final AggregatedHttpResponse res = client.blocking().get("/500");
            assertThat(res.status().code()).isEqualTo(500);
            // All 3 retry attempts should have been made
            assertThat(captor.get().log().children()).hasSize(3);
        }
    }
}
