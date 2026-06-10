/*
 * Copyright 2025 LINE Corporation
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

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.TlsKeyPair;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.util.AttributeKey;

class ConnectionAcceptorTest {

    private static final AttributeKey<String> TEST_ATTR =
            AttributeKey.valueOf(ConnectionAcceptorTest.class, "TEST_ATTR");

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.http(0)
              .https(0)
              .tls(TlsKeyPair.ofSelfSigned())
              .connectionAcceptor(ConnectionAcceptor.of(ctx -> {
                  ctx.setAttr(TEST_ATTR, "from-connection");
                  return true;
              }))
              .service("/", (ctx, req) -> connectionContextJson(ctx))
              .service("/override", (ctx, req) -> {
                  ctx.setAttr(TEST_ATTR, "from-request");
                  return connectionContextJson(ctx);
              });
        }
    };

    @RegisterExtension
    static final ServerExtension rejectServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.http(0)
              .https(0)
              .tls(TlsKeyPair.ofSelfSigned())
              .connectionAcceptor(ConnectionAcceptor.of(ctx -> false))
              .service("/", (ctx, req) -> HttpResponse.of("should not reach"));
        }
    };

    @RegisterExtension
    static final ServerExtension throwingServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.http(0)
              .https(0)
              .tls(TlsKeyPair.ofSelfSigned())
              .connectionAcceptor(ConnectionAcceptor.of(ctx -> {
                  throw new RuntimeException("acceptor failed");
              }))
              .service("/", (ctx, req) -> HttpResponse.of("should not reach"));
        }
    };

    private static HttpResponse connectionContextJson(ServiceRequestContext ctx) {
        final ConnectionContext connCtx = ctx.connectionContext();
        final Map<String, Object> map = new HashMap<>();
        map.put("sni", connCtx.sniHostname());
        map.put("alpn", connCtx.alpnProtocols());
        map.put("protocol", connCtx.sessionProtocol().toString());
        map.put("connAttr", connCtx.attr(TEST_ATTR));
        map.put("ctxAttr", ctx.attr(TEST_ATTR));
        return HttpResponse.ofJson(map);
    }

    @Test
    void httpsAcceptorAcceptsConnection() {
        try (ClientFactory factory = ClientFactory.builder().tlsNoVerify().build()) {
            final BlockingWebClient client = WebClient.builder(server.uri(SessionProtocol.HTTPS))
                                                      .factory(factory)
                                                      .build()
                                                      .blocking();
            final String body = client.get("/").contentUtf8();
            assertThatJson(body).node("protocol").isEqualTo("https");
            // SNI is null when connecting to an IP address (127.0.0.1)
            assertThatJson(body).node("sni").isEqualTo(null);
            assertThatJson(body).node("alpn").isEqualTo("[\"h2\",\"http/1.1\"]");
            assertThatJson(body).node("connAttr").isEqualTo("from-connection");
            assertThatJson(body).node("ctxAttr").isEqualTo("from-connection");
        }
    }

    @Test
    void httpAcceptorAcceptsConnection() {
        try (ClientFactory factory = ClientFactory.builder().build()) {
            final BlockingWebClient client = WebClient.builder(server.uri(SessionProtocol.HTTP))
                                                      .factory(factory)
                                                      .build()
                                                      .blocking();
            final String body = client.get("/").contentUtf8();
            assertThatJson(body).node("sni").isEqualTo(null);
            assertThatJson(body).node("alpn").isEqualTo("[]");
            assertThatJson(body).node("connAttr").isEqualTo("from-connection");
            assertThatJson(body).node("ctxAttr").isEqualTo("from-connection");
        }
    }

    @Test
    void requestContextAttrOverridesConnectionAttr() {
        try (ClientFactory factory = ClientFactory.builder().tlsNoVerify().build()) {
            final BlockingWebClient client = WebClient.builder(server.uri(SessionProtocol.HTTPS))
                                                      .factory(factory)
                                                      .build()
                                                      .blocking();
            final String body = client.get("/override").contentUtf8();
            assertThatJson(body).node("ctxAttr").isEqualTo("from-request");
            assertThatJson(body).node("connAttr").isEqualTo("from-connection");
        }
    }

    static Stream<Arguments> rejectAndThrowServers() {
        return Stream.of(
                Arguments.of(rejectServer, SessionProtocol.HTTP),
                Arguments.of(rejectServer, SessionProtocol.HTTPS),
                Arguments.of(throwingServer, SessionProtocol.HTTP),
                Arguments.of(throwingServer, SessionProtocol.HTTPS)
        );
    }

    @ParameterizedTest
    @MethodSource("rejectAndThrowServers")
    void acceptorRejectsOrThrows(ServerExtension server, SessionProtocol protocol) {
        try (ClientFactory factory = ClientFactory.builder().tlsNoVerify().build()) {
            final BlockingWebClient client = WebClient.builder(server.uri(protocol))
                                                      .factory(factory)
                                                      .build()
                                                      .blocking();
            assertThatThrownBy(() -> client.get("/"))
                    .isInstanceOf(UnprocessedRequestException.class);
        }
    }
}
