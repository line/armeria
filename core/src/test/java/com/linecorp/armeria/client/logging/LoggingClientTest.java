/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.armeria.client.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.Charset;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import org.junit.ClassRule;
import org.junit.Test;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.HttpClientBuilder;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAvailability;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.testing.server.ServerRule;

public class LoggingClientTest {

    static class MyHttpClient {
        private final HttpClient client;
        @Nullable
        private CompletableFuture<RequestLog> waitingFuture;

        MyHttpClient(String uri, int reqLength, int resLength) {
            client = new HttpClientBuilder(serverRule.uri(uri))
                    .decorator(new LoggingClientBuilder().requestLogLevel(LogLevel.INFO)
                                                         .successfulResponseLogLevel(LogLevel.INFO)
                                                         .requestContentPreview(reqLength)
                                                         .responseContentPreview(resLength)
                                                         .newDecorator())
                    .decorator((delegate, ctx, req) -> {
                        if (waitingFuture != null) {
                            ctx.log().addListener(waitingFuture::complete, RequestLogAvailability.COMPLETE);
                        }
                        return delegate.execute(ctx, req);
                    }).build();
        }

        public RequestLog get(String path) throws Exception {
            waitingFuture = new CompletableFuture<>();
            getBody(path).aggregate().join();
            final RequestLog log = waitingFuture.get();
            waitingFuture = null;
            return log;
        }

        public HttpResponse getBody(String path) throws Exception {
            return client.execute(HttpHeaders.of(HttpMethod.GET, path).set(HttpHeaderNames.ACCEPT, "utf-8")
                                             .set(HttpHeaderNames.CONTENT_TYPE,
                                                  MediaType.ANY_TEXT_TYPE.toString()));
        }

        public RequestLog post(String path, byte[] content, MediaType contentType) throws Exception {
            waitingFuture = new CompletableFuture<>();
            client.execute(HttpHeaders.of(HttpMethod.POST, path)
                                      .contentType(contentType)
                                      .set(HttpHeaderNames.ACCEPT, "utf-8")
                                      .set(HttpHeaderNames.CONTENT_TYPE, MediaType.ANY_TEXT_TYPE.toString()),
                           content);
            final RequestLog log = waitingFuture.get();
            waitingFuture = null;
            return log;
        }

        public RequestLog post(String path, String content, Charset charset, MediaType contentType)
                throws Exception {
            return post(path, content.getBytes(charset), contentType);
        }

        public RequestLog post(String path, String content, MediaType contentType) throws Exception {
            return post(path, content.getBytes(), contentType);
        }

        public RequestLog post(String path, String content) throws Exception {
            return post(path, content.getBytes(), MediaType.ANY_TEXT_TYPE);
        }

        public RequestLog post(String path) throws Exception {
            return post(path, "");
        }
    }

    @ClassRule
    public static final ServerRule serverRule = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.annotatedService("/example", new Object() {
                @Get("/get")
                public String get() {
                    return "test";
                }

                @Get("/get-unicode")
                public HttpResponse getUnicode() {
                    return HttpResponse.of(MediaType.ANY_TEXT_TYPE, "안녕");
                }

                @Get("/get-audio")
                public HttpResponse getBinary() {
                    return HttpResponse.of(HttpStatus.OK, MediaType.BASIC_AUDIO, new byte[] {1,2,3,4});
                }

                @Get("/get-json")
                public HttpResponse getJson() {
                    return HttpResponse.of(MediaType.JSON, "{\"value\":1}");
                }

                @Get("/get-longstring")
                public String getLongString() {
                    StringBuilder builder = new StringBuilder(10000);
                    for (int i = 0; i < 10000; i++) {
                        builder.append("a");
                    }
                    return builder.toString();
                }

                @Post("/post")
                public String post() {
                    return "abcdefghijkmnopqrstu";
                }
            });
        }
    };

    @Test
    public void testResponseContentPreview() throws Exception {
        final MyHttpClient client = new MyHttpClient("/example", 10, 10);
        assertThat(client.get("/get").responseContentPreview()).isEqualTo("test");
        assertThat(client.getBody("/get").aggregate().get()
                         .content().toString(Charset.defaultCharset())).isEqualTo("test");
        assertThat(client.get("/get-unicode").responseContentPreview()).isEqualTo("안녕");
        assertThat(client.getBody("/get-unicode").aggregate().get()
                         .content().toString(Charset.defaultCharset())).isEqualTo("안녕");
        assertThat(client.getBody("/get-audio").aggregate().get()
                         .content().array()).containsExactly(new byte[] {1, 2, 3, 4});
        assertThat(client.get("/get-json").responseContentPreview()).isEqualTo("{\"value\":1");
        assertThat(client.getBody("/get-json").aggregate().get()
                         .content().toString(Charset.defaultCharset())).isEqualTo("{\"value\":1}");
        assertThat(client.post("/post").responseContentPreview()).isEqualTo("abcdefghij");
        assertThat(client.post("/post", "abcdefghijkmno").requestContentPreview()).isEqualTo("abcdefghij");
        assertThat(client.get("/get-longstring").responseContentPreview()).isEqualTo("aaaaaaaaaa");
    }

    @Test
    public void testBinary() throws Exception {
        final MyHttpClient client = new MyHttpClient("/example", 10, 10);
        assertThat(client.get("/get-audio").responseContentPreview()).isEqualTo("<unmappable charset>");
    }
}
