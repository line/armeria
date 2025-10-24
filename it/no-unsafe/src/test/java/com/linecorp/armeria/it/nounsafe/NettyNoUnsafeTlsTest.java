/*
 * Copyright 2025 LY Corporation
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

package com.linecorp.armeria.it.nounsafe;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junitpioneer.jupiter.SetSystemProperty;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.SelfSignedCertificateExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

@SetSystemProperty(key = "io.netty.noUnsafe", value = "true")
@SetSystemProperty(key = "io.netty.allocator.type", value = "pooled")
class NettyNoUnsafeTlsTest {

    @Order(0)
    @RegisterExtension
    static SelfSignedCertificateExtension scc = new SelfSignedCertificateExtension();

    @Order(1)
    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.tls(scc.tlsKeyPair());
            sb.service("/hello", (ctx, req) -> HttpResponse.of("Hello, World!"));
        }
    };

    @Test
    void shouldSucceedWithNoUnsafe() {
        try (ClientFactory factory =
                     ClientFactory.builder()
                                  .tlsCustomizer(scb -> scb.trustManager(scc.certificate()))
                                  .build()) {
            final BlockingWebClient client = WebClient.builder(server.httpsUri())
                                                      .factory(factory)
                                                      .build()
                                                      .blocking();
            assertThat(client.get("/hello").contentUtf8()).isEqualTo("Hello, World!");
        }
    }
}
