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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Queue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

class HeadMethodLeakTest {

    private static Queue<ByteBuf> bufs;

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.http(0);
            sb.https(0);
            sb.tlsSelfSigned();
            sb.service("/{number}", new HttpService() {

                @Override
                public ExchangeType exchangeType(RoutingContext routingContext) {
                    return ExchangeType.valueOf(
                            QueryParams.fromQueryString(routingContext.query()).get("exchangeType"));
                }

                @Override
                public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                    final int number = Integer.parseInt(ctx.pathParam("number"));
                    final HttpObject[] objs = new HttpObject[number + 1];
                    objs[0] = ResponseHeaders.of(HttpStatus.OK);
                    for (int i = 0; i < number; i++) {
                        final ByteBuf buf = Unpooled.buffer().writeBytes(new byte[] { 1, 2, 3, 4 });
                        bufs.add(buf);
                        objs[i + 1] = HttpData.wrap(buf);
                    }
                    if (number <= 5) {
                        return HttpResponse.of(objs);
                    } else {
                        final HttpResponseWriter stream = HttpResponse.streaming();
                        for (HttpObject obj : objs) {
                            stream.write(obj);
                        }
                        stream.close();
                        return stream;
                    }
                }
            });
        }
    };

    @BeforeEach
    void setUp() {
        bufs = new LinkedBlockingDeque<>();
    }

    @ArgumentsSource(HeadRequestOptionsProvider.class)
    @ParameterizedTest
    void shouldReleaseDataWhenHeadMethodIsRequested(int numChunks, SessionProtocol protocol,
                                                    ExchangeType exchangeType) throws InterruptedException {
        final BlockingWebClient client = WebClient.builder(server.uri(protocol))
                                                  .factory(ClientFactory.insecure())
                                                  .build()
                                                  .blocking();
        final AggregatedHttpResponse response = client.prepare()
                                                      .method(HttpMethod.HEAD)
                                                      .path("/{number}")
                                                      .pathParam("number", numChunks)
                                                      .queryParam("exchangeType", exchangeType.name())
                                                      .execute();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.content().isEmpty()).isTrue();

        final ServiceRequestContext sctx = server.requestContextCaptor().take();
        // Waits for the server response to be cancelled.
        sctx.log().whenComplete().join();
        // Make sure all bufs were released by HttpResponseSubscriber.
        for (ByteBuf buf : bufs) {
            assertThat(buf.refCnt()).isZero();
        }
    }

    private static class HeadRequestOptionsProvider implements ArgumentsProvider {

        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) throws Exception {
            final Stream.Builder<Arguments> builder = Stream.builder();
            for (int i = 0; i < 20; i++) {
                for (SessionProtocol protocol : SessionProtocol.values()) {
                    if (protocol == SessionProtocol.PROXY || protocol == SessionProtocol.UNDEFINED) {
                        continue;
                    }
                    for (ExchangeType exchangeType : ExchangeType.values()) {
                        builder.add(Arguments.of(i, protocol, exchangeType));
                    }
                }
            }
            return builder.build();
        }
    }
}
