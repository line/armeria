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

package com.linecorp.armeria.common.logging;

import static com.linecorp.armeria.common.logging.ContentPreviewerFactoryBuilder.hexDumpProducer;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.base.Strings;

import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.WebClientBuilder;
import com.linecorp.armeria.client.logging.ContentPreviewingClient;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.MediaTypeSet;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.logging.ContentPreviewingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class ContentPreviewerTest {

    static class MyHttpClient {
        private final WebClient client;

        MyHttpClient(String uri, int maxLength) {
            final WebClientBuilder builder = WebClient.builder(serverExtension.httpUri().resolve(uri));

            final ContentPreviewerFactory factory = contentPreviewerFactory(maxLength);

            client = builder.decorator(ContentPreviewingClient.newDecorator(factory))
                            .decorator(LoggingClient.builder()
                                                    .requestLogLevel(LogLevel.INFO)
                                                    .successfulResponseLogLevel(LogLevel.INFO)
                                                    .newDecorator())
                            .build();
        }

        RequestLog get(String path) throws Exception {
            try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
                getBody(path).aggregate();
                return captor.get().log().whenComplete().join();
            }
        }

        HttpResponse getBody(String path) throws Exception {
            return client.execute(RequestHeaders.of(HttpMethod.GET, path,
                                                    HttpHeaderNames.ACCEPT, "utf-8",
                                                    HttpHeaderNames.CONTENT_TYPE, MediaType.ANY_TEXT_TYPE));
        }

        HttpResponse postBody(String path, byte[] content, MediaType contentType) {
            return client.execute(RequestHeaders.of(HttpMethod.POST, path,
                                                    HttpHeaderNames.CONTENT_TYPE, contentType,
                                                    HttpHeaderNames.ACCEPT, "utf-8"), content);
        }

        RequestLog post(String path, byte[] content, MediaType contentType) throws Exception {
            try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
                postBody(path, content, contentType).aggregate();
                return captor.get().log().whenComplete().join();
            }
        }

        RequestLog post(String path, String content) throws Exception {
            return post(path, content.getBytes(), MediaType.ANY_TEXT_TYPE);
        }

        RequestLog post(String path) throws Exception {
            return post(path, "");
        }
    }

    private static ContentPreviewerFactory contentPreviewerFactory(int maxLength) {
        return ContentPreviewerFactory.builder()
                                      .maxLength(maxLength)
                                      .defaultCharset(StandardCharsets.UTF_8)
                                      .disable(MediaTypeSet.of(MediaType.BASIC_AUDIO))
                                      .build();
    }

    static class MyHttpServer {
        @Nullable
        private CompletableFuture<RequestLog> waitingFuture;

        Client newClient(String path) {
            return new Client(path);
        }

        class Client {
            private final WebClient client;

            Client(String path) {
                client = WebClient.of(serverExtension.httpUri().resolve(path));
            }

            RequestLog get(String path) throws Exception {
                waitingFuture = new CompletableFuture<>();
                getBody(path).aggregate().join();
                final RequestLog log = waitingFuture.get();
                waitingFuture = null;
                return log;
            }

            HttpResponse getBody(String path) throws Exception {
                return client.execute(RequestHeaders.of(HttpMethod.GET, path,
                                                        HttpHeaderNames.ACCEPT, "utf-8",
                                                        HttpHeaderNames.CONTENT_TYPE, MediaType.ANY_TEXT_TYPE));
            }

            RequestLog post(String path, byte[] content, MediaType contentType) throws Exception {
                waitingFuture = new CompletableFuture<>();
                client.execute(RequestHeaders.of(HttpMethod.POST, path,
                                                 HttpHeaderNames.CONTENT_TYPE, contentType,
                                                 HttpHeaderNames.ACCEPT, "utf-8",
                                                 HttpHeaderNames.CONTENT_TYPE, MediaType.ANY_TEXT_TYPE),
                               content).aggregate();
                final RequestLog log = waitingFuture.get();
                waitingFuture = null;
                return log;
            }

            RequestLog post(String path, String content) throws Exception {
                return post(path, content.getBytes(), MediaType.ANY_TEXT_TYPE);
            }

            RequestLog post(String path) throws Exception {
                return post(path, "");
            }
        }

        void build(ServerBuilder sb) {
            sb.decorator(ContentPreviewingService.newDecorator(contentPreviewerFactory(10)));
            sb.decorator(delegate -> (ctx, req) -> {
                if (waitingFuture != null) {
                    ctx.log().whenComplete().thenAccept(waitingFuture::complete);
                }
                return delegate.serve(ctx, req);
            });
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
                    return HttpResponse.of(HttpStatus.OK, MediaType.BASIC_AUDIO, new byte[] { 1, 2, 3, 4 });
                }

                @Get("/get-json")
                public HttpResponse getJson() {
                    return HttpResponse.of(MediaType.JSON, "{\"value\":1}");
                }

                @Get("/get-longstring")
                public String getLongString() {
                    return Strings.repeat("a", 10000);
                }

                @Post("/post")
                public String post(String requestContent) {
                    return "abcdefghijkmnopqrstu";
                }
            });
        }
    }

    private static final MyHttpServer server = new MyHttpServer();

    @RegisterExtension
    static final ServerExtension serverExtension = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            server.build(sb);
        }
    };

    @RepeatedTest(100000)
    void testClientLog() throws Exception {
        final MyHttpClient client = new MyHttpClient("/example", 10);
        assertThat(client.get("/get").responseContentPreview()).isEqualTo("test");
        assertThat(client.getBody("/get").aggregate().get()
                         .content().toStringUtf8()).isEqualTo("test");
        assertThat(client.getBody("/get-unicode").aggregate().get()
                         .content().toStringUtf8()).isEqualTo("안녕");
        assertThat(client.get("/get-unicode").responseContentPreview()).isEqualTo("안녕");
        assertThat(client.getBody("/get-audio").aggregate().get()
                         .content().array()).containsExactly(new byte[] { 1, 2, 3, 4 });
        assertThat(client.get("/get-audio").responseContentPreview()).isNull();
        assertThat(client.get("/get-json").responseContentPreview()).isEqualTo("{\"value\":1");
        assertThat(client.getBody("/get-json").aggregate().get()
                         .content().toStringUtf8()).isEqualTo("{\"value\":1}");
        assertThat(client.post("/post").responseContentPreview()).isEqualTo("abcdefghij");
        assertThat(client.post("/post", "abcdefghijkmno").requestContentPreview()).isEqualTo("abcdefghij");
        assertThat(client.get("/get-longstring").responseContentPreview()).isEqualTo("aaaaaaaaaa");
    }

    @Test
    void testServerLog() throws Exception {
        final MyHttpServer.Client client = server.newClient("/example");

        assertThat(client.get("/get").responseContentPreview()).isEqualTo("test");
        assertThat(client.getBody("/get").aggregate().get()
                         .content().toStringUtf8()).isEqualTo("test");
        assertThat(client.get("/get-unicode").responseContentPreview()).isEqualTo("안녕");
        assertThat(client.getBody("/get-unicode").aggregate().get()
                         .content().toStringUtf8()).isEqualTo("안녕");
        assertThat(client.getBody("/get-audio").aggregate().get()
                         .content().array()).containsExactly(new byte[] { 1, 2, 3, 4 });
        assertThat(client.get("/get-audio").responseContentPreview()).isNull();
        assertThat(client.get("/get-json").responseContentPreview()).isEqualTo("{\"value\":1");
        assertThat(client.getBody("/get-json").aggregate().get()
                         .content().toStringUtf8()).isEqualTo("{\"value\":1}");
        assertThat(client.post("/post").responseContentPreview()).isEqualTo("abcdefghij");
        assertThat(client.post("/post", "abcdefghijkmno").requestContentPreview()).isEqualTo("abcdefghij");
        assertThat(client.get("/get-longstring").responseContentPreview()).isEqualTo("aaaaaaaaaa");
    }

    @Test
    void testCustomPreviewer() throws Exception {
        ContentPreviewer previewer = hexDumpContentPreviewer();
        previewer.onData(HttpData.wrap(new byte[] { 1, 2, 3, 4 }));
        assertThat(previewer.produce()).isEqualTo("01020304");

        previewer = hexDumpContentPreviewer();
        previewer.onData(HttpData.wrap(new byte[] { 1, 2, 3 }));
        previewer.onData(HttpData.wrap(new byte[] { 4, 5 }));
        assertThat(previewer.produce()).isEqualTo("0102030405");
        assertThat(previewer.produce()).isEqualTo("0102030405");
    }

    ContentPreviewer hexDumpContentPreviewer() {
        return new ProducerBasedContentPreviewer(100, HttpHeaders.of(), hexDumpProducer());
    }
}
