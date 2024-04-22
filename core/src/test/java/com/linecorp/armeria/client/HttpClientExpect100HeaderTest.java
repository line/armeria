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
 */

package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestWriter;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

/**
 * This test is to check the behavior of the HttpClient when the 'Expect: 100-continue' header is set.
 */
final class HttpClientExpect100HeaderTest {
    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {

            sb.service("/continue", (ctx, req) -> {
                if (req.headers().contains(HttpHeaderNames.EXPECT, "100-continue")) {
                    return HttpResponse.of(HttpStatus.CONTINUE);
                }
                return HttpResponse.of(HttpStatus.OK);
            });

            sb.service("/expectation-failed", (ctx, req) ->
                    HttpResponse.of(HttpStatus.EXPECTATION_FAILED));

            sb.service("/stream-continue", (ctx, req) -> {
                ctx.clearRequestTimeout();
                final HttpResponseWriter res = HttpResponse.streaming();
                if (req.headers().contains(HttpHeaderNames.EXPECT, "100-continue")) {
                    res.write(ResponseHeaders.of(HttpStatus.CONTINUE));
                }
                req.subscribe(new Subscriber<HttpObject>() {
                    @Override
                    public void onSubscribe(Subscription s) {
                        s.request(Long.MAX_VALUE);
                    }

                    @Override
                    public void onNext(HttpObject httpObject) {
                    }

                    @Override
                    public void onError(Throwable t) {
                    }

                    @Override
                    public void onComplete() {
                        res.write(ResponseHeaders.of(HttpStatus.OK));
                        res.close();
                    }
                }, ctx.eventLoop());
                return res;
            });

            sb.service("/stream-expectation-failed", (ctx, req) -> {
                ctx.clearRequestTimeout();
                final HttpResponseWriter res = HttpResponse.streaming();
                req.subscribe(new Subscriber<HttpObject>() {
                    @Override
                    public void onSubscribe(Subscription s) {
                        s.request(Long.MAX_VALUE);
                    }

                    @Override
                    public void onNext(HttpObject httpObject) {
                    }

                    @Override
                    public void onError(Throwable t) {
                    }

                    @Override
                    public void onComplete() {
                        res.write(ResponseHeaders.of(HttpStatus.EXPECTATION_FAILED));
                    }
                }, ctx.eventLoop());
                return res;
            });
        }
    };

    @Nested
    class AggregatedHttpRequestHandlerTest {
        @ParameterizedTest
        @ValueSource(booleans = { true, false })
        void sendRequest(boolean useHttp1) throws Exception {
            assertStatus("/continue", useHttp1, HttpStatus.OK);
            assertStatus("/expectation-failed", useHttp1, HttpStatus.EXPECTATION_FAILED);
        }

        private void assertStatus(String path, boolean useHttp1, HttpStatus expectedStatus) {
            final ClientFactory factory = ClientFactory.builder()
                                                       .preferHttp1(useHttp1)
                                                       .build();
            final WebClient client = WebClient.builder(server.httpUri())
                                              .factory(factory)
                                              .build();
            final AggregatedHttpResponse response = client.prepare()
                                                          .get(path)
                                                          .header(HttpHeaderNames.EXPECT, "100-continue")
                                                          .execute()
                                                          .aggregate()
                                                          .join();

            assertThat(response.status()).isEqualTo(expectedStatus);
        }
    }

    @Nested
    class HttpRequestHandlerSubscriberTest {
        @Test
        void sendRequest() throws Exception {
            assertStatus("/stream-continue", HttpStatus.OK);
            assertStatus("/stream-expectation-failed", HttpStatus.EXPECTATION_FAILED);
        }

        private void assertStatus(String path, HttpStatus expectedStatus) {
            final WebClient client = WebClient.builder(server.httpUri()).build();
            final RequestHeaders headers = RequestHeaders.of(HttpMethod.GET, path,
                                                             HttpHeaderNames.EXPECT, "100-continue");
            final HttpRequestWriter req = HttpRequest.streaming(headers);

            client.execute(req).aggregate().thenAccept(res -> {
                assertThat(res.status()).isEqualTo(expectedStatus);
            });

            req.close();
        }
    }

    private HttpClientExpect100HeaderTest() {}
}
