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

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.net.ssl.SSLEngine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.client.SessionProtocolNegotiationException;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.internal.common.util.SslContextUtil;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.traffic.ChannelTrafficShapingHandler;

class ServerChildChannelPipelineCustomizerTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.http(0);
            sb.https(0);
            sb.tlsSelfSigned();
            sb.childChannelPipelineCustomizer(
                    pipeline ->  pipeline.addLast(new ChannelTrafficShapingHandler(1024, 0)));
            sb.service("/", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req)
                        throws Exception {
                    return HttpResponse.of(
                        ResponseHeaders.of(HttpStatus.OK, "header1", Strings.repeat("a", 2048)));
                }
            });
        }
    };

    static final SSLEngine sslEngine =  SslContextUtil.createSslContext(
            SslContextBuilder::forClient,
            /* forceHttp1 */ true,
            /* tlsAllowUnsafeCiphers */ false,
            ImmutableList.of()).newEngine(ByteBufAllocator.DEFAULT);

    @RegisterExtension
    static ServerExtension server2 = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.http(8080);
            sb.childChannelPipelineCustomizer(
                    pipeline ->  pipeline.addFirst(new SslHandler(sslEngine)));
            sb.service("/", (ctx, req) -> HttpResponse.of("content"));
        }
    };

    @EnumSource(value = SessionProtocol.class, names = {"H1", "H1C"})
    @ParameterizedTest
    void testResponseTimeout(SessionProtocol protocol) {
        final RequestHeaders requestHeaders = RequestHeaders.of(HttpMethod.GET, "/");
        final ClientFactory clientFactory = ClientFactory.builder()
                                                         .tlsNoVerify()
                                                         .build();

        // using h1 or h1c since http2 compresses headers
        assertThatThrownBy(() -> WebClient.builder(server.uri(protocol))
                                          .responseTimeoutMillis(1000)
                                          .factory(clientFactory)
                                          .build()
                                          .blocking()
                                          .execute(requestHeaders, "content"))
                .isInstanceOf(ResponseTimeoutException.class);
    }

    @Test
    void testSessionProtocolNegotiationException() {
        final RequestHeaders requestHeaders = RequestHeaders.of(HttpMethod.GET, "/");

        // using h2c
        assertThatThrownBy(() -> WebClient.builder(server2.uri(SessionProtocol.H2C))
                 .build()
                 .blocking()
                 .execute(requestHeaders, "content"))
        .isInstanceOf(UnprocessedRequestException.class)
        .hasCauseInstanceOf(SessionProtocolNegotiationException.class);
    }
}
