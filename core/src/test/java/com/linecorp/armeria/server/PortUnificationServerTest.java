/*
 * Copyright 2018 LINE Corporation
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

import static com.linecorp.armeria.common.SessionProtocol.HTTP;
import static com.linecorp.armeria.common.SessionProtocol.HTTPS;
import static com.linecorp.armeria.common.SessionProtocol.PROXY;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.internal.testing.UniqueProtocolsProvider;
import com.linecorp.armeria.testing.junit.server.ServerExtension;

class PortUnificationServerTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.port(0, PROXY, HTTP, HTTPS);
            sb.tlsSelfSigned();
            sb.service("/", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
                    return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8,
                                           ctx.sessionProtocol().name());
                }
            });
        }
    };

    @Test
    void httpAndHttpsUsesSamePort() {
        assertThat(server.httpPort()).isEqualTo(server.httpsPort());
    }

    @ParameterizedTest
    @ArgumentsSource(UniqueProtocolsProvider.class)
    void test(SessionProtocol protocol) throws Exception {
        final WebClient client = WebClient.builder(server.uri(protocol))
                                          .factory(ClientFactory.insecure())
                                          .build();
        final AggregatedHttpResponse response = client.execute(HttpRequest.of(HttpMethod.GET, "/"))
                                                      .aggregate().join();
        assertThat(response.contentUtf8()).isEqualTo(protocol.name());
    }
}
