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

import javax.net.ssl.SSLException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.internal.common.ReadSuppressingHandler;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.SelfSignedCertificateExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;

class H2WithoutAlpnTest {

    @RegisterExtension
    static SelfSignedCertificateExtension cert = new SelfSignedCertificateExtension();

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws SSLException {

            sb.service("/", (ctx, req) -> HttpResponse.of("OK"));
            sb.requestTimeoutMillis(0);
            sb.childChannelPipelineCustomizer(pipeline -> {
                final SslContext sslContext;
                try {
                    sslContext = SslContextBuilder.forServer(cert.certificateFile(), cert.privateKeyFile())
                                                  .build();
                } catch (SSLException e) {
                    throw new RuntimeException(e);
                }
                final ChannelHandlerContext context = pipeline.context(ReadSuppressingHandler.class);
                pipeline.addAfter(context.name(), null, sslContext.newHandler(pipeline.channel().alloc()));
            });
        }
    };

    @Test
    void shouldSupportH2WithoutAlpn() {
        try (ClientFactory factory = ClientFactory.builder()
                                                  .useHttp2WithoutALPN(true)
                                                  .connectTimeoutMillis(Long.MAX_VALUE)
                                                  .tlsCustomizer(b -> b.trustManager(cert.certificate()))
                                                  .build()) {

            final BlockingWebClient client = WebClient.builder("h2://127.0.0.1:" + server.httpPort())
                                                      .factory(factory)
                                                      .responseTimeoutMillis(Long.MAX_VALUE)
                                                      .build()
                                                      .blocking();

            final AggregatedHttpResponse response = client.get("/");
            assertThat(response.contentUtf8()).isEqualTo("OK");
        }
    }
}
