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

package com.linecorp.armeria.common.encoding;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.encoding.DecodingClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.encoding.EncodingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class StreamDecoderCompatibleTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/encoding-test", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req)
                        throws Exception {
                    return HttpResponse.of(
                            ResponseHeaders.of(HttpStatus.OK),
                            HttpData.ofUtf8("some content to compress "),
                            HttpData.ofUtf8("more content to compress"));
                }
            }.decorate(EncodingService.newDecorator()));
        }
    };

    private static final ClientFactory clientFactory = ClientFactory.ofDefault();

    @Test
    void httpDecodingWithNewDecoder() throws Exception {
        final WebClient client = WebClient.builder(server.httpUri())
                                          .factory(clientFactory)
                                          .decorator(DecodingClient.newDecorator(
                                                  StreamDecoderFactory.gzip()))
                                          .build();

        final AggregatedHttpResponse response =
                client.execute(RequestHeaders.of(HttpMethod.GET, "/encoding-test")).aggregate().get();
        assertThat(response.headers().get(HttpHeaderNames.CONTENT_ENCODING)).isEqualTo("gzip");
        assertThat(response.contentUtf8()).isEqualTo(
                "some content to compress more content to compress");
    }

    @Test
    void httpDecodingWithOldDecoder() throws Exception {
        final WebClient client = WebClient.builder(server.httpUri())
                                          .factory(clientFactory)
                                          .decorator(DecodingClient.newDecorator(
                                                  com.linecorp.armeria.client.encoding.StreamDecoderFactory
                                                          .gzip()))
                                          .build();

        final AggregatedHttpResponse response =
                client.execute(RequestHeaders.of(HttpMethod.GET, "/encoding-test")).aggregate().get();
        assertThat(response.headers().get(HttpHeaderNames.CONTENT_ENCODING)).isEqualTo("gzip");
        assertThat(response.contentUtf8()).isEqualTo(
                "some content to compress more content to compress");
    }
}
