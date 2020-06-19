/*
 * Copyright 2020 LINE Corporation
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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit.server.ServerExtension;

class WebClientBuilderTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/echo-path", (ctx, req) -> {
                String pathAndQuery = ctx.path();
                if (ctx.query() != null) {
                    pathAndQuery += '?' + ctx.query();
                }
                return HttpResponse.of(pathAndQuery);
            });
        }
    };

    @Test
    void uriWithNonePlusProtocol() throws Exception {
        final WebClient client = WebClient.builder("none+https://google.com/").build();
        assertThat(client.uri().toString()).isEqualTo("https://google.com/");
    }

    @Test
    void uriWithoutNone() {
        final WebClient client = WebClient.builder("https://google.com/").build();
        assertThat(client.uri().toString()).isEqualTo("https://google.com/");
    }

    @Test
    void endpointWithoutPath() {
        final WebClient client = WebClient.builder("http", Endpoint.of("127.0.0.1"))
                                          .build();
        assertThat(client.uri().toString()).isEqualTo("http://127.0.0.1/");
    }

    @Test
    void endpointWithPath() {
        final WebClient client = WebClient.builder("http", Endpoint.of("127.0.0.1"), "/foo")
                                          .build();
        assertThat(client.uri().toString()).isEqualTo("http://127.0.0.1/foo");
    }

    @Test
    void authorityHeader() {
        final String path = "/echo-path?foo=bar";
        final RequestHeadersBuilder requestHeadersBuilder =
                RequestHeaders.builder()
                              .authority("localhost:" + server.httpPort())
                              .scheme("h2c")
                              .add("param1", "val1")
                              .path(path);

        final AggregatedHttpRequest request = AggregatedHttpRequest.of(
                requestHeadersBuilder.method(HttpMethod.GET).build());
        final HttpResponse response = WebClient.of().execute(request);
        assertThat(response.aggregate().join().contentUtf8()).isEqualTo(path);
    }

    @Test
    void keepCustomFactory() {
        final ClientFactory factory = ClientFactory.builder()
                                                   .option(ClientFactoryOption.HTTP1_MAX_CHUNK_SIZE, 100)
                                                   .build();
        final ClientOptions options = ClientOptions.of(ClientOption.RESPONSE_TIMEOUT_MILLIS.newValue(200L));
        final WebClient webClient = WebClient.builder("http://foo")
                                             .factory(factory)
                                             .options(options)
                                             .build();

        final ClientOptions clientOptions = webClient.options();
        assertThat(clientOptions.get(ClientOption.RESPONSE_TIMEOUT_MILLIS)).isEqualTo(200);
        final ClientFactory clientFactory = clientOptions.factory();
        assertThat(clientFactory.options().get(ClientFactoryOption.HTTP1_MAX_CHUNK_SIZE)).isEqualTo(100);
    }

    @Test
    void keepLastFactory_by_options() {
        final ClientFactory optionClientFactory =
                ClientFactory.builder()
                             .option(ClientFactoryOption.HTTP1_MAX_CHUNK_SIZE, 200)
                             .build();
        final ClientOptions options = ClientOptions.of(ClientOption.RESPONSE_TIMEOUT_MILLIS.newValue(200L),
                                                       ClientOption.FACTORY.newValue(optionClientFactory));

        final ClientFactory factory = ClientFactory.builder()
                                                   .option(ClientFactoryOption.HTTP1_MAX_CHUNK_SIZE, 100)
                                                   .build();

        final WebClient webClient = WebClient.builder("http://foo")
                                             .factory(factory)
                                             .options(options)
                                             .build();

        final ClientOptions clientOptions = webClient.options();
        assertThat(clientOptions.get(ClientOption.RESPONSE_TIMEOUT_MILLIS)).isEqualTo(200);
        final ClientFactory clientFactory = clientOptions.factory();
        assertThat(clientFactory.options().get(ClientFactoryOption.HTTP1_MAX_CHUNK_SIZE)).isEqualTo(200);
    }

    @Test
    void keepLastFactory_by_factory() {
        final ClientFactory optionClientFactory =
                ClientFactory.builder()
                             .option(ClientFactoryOption.HTTP1_MAX_CHUNK_SIZE, 200)
                             .build();
        final ClientOptions options = ClientOptions.of(ClientOption.RESPONSE_TIMEOUT_MILLIS.newValue(200L),
                                                       ClientOption.FACTORY.newValue(optionClientFactory));

        final ClientFactory factory = ClientFactory.builder()
                                                   .option(ClientFactoryOption.HTTP1_MAX_CHUNK_SIZE, 100)
                                                   .build();

        final WebClient webClient = WebClient.builder("http://foo")
                                             .options(options)
                                             .factory(factory)
                                             .build();

        final ClientOptions clientOptions = webClient.options();
        assertThat(clientOptions.get(ClientOption.RESPONSE_TIMEOUT_MILLIS)).isEqualTo(200);
        final ClientFactory clientFactory = clientOptions.factory();
        assertThat(clientFactory.options().get(ClientFactoryOption.HTTP1_MAX_CHUNK_SIZE)).isEqualTo(100);
    }
}
