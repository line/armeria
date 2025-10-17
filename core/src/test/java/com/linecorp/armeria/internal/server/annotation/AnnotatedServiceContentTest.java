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

package com.linecorp.armeria.internal.server.annotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.base.Objects;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.ContentDisposition;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseEntity;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.multipart.BodyPart;
import com.linecorp.armeria.common.multipart.Multipart;
import com.linecorp.armeria.common.multipart.MultipartFile;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.ProducesJson;
import com.linecorp.armeria.server.file.HttpFile;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class AnnotatedServiceContentTest {

    @RegisterExtension
    static EventLoopExtension eventLoop = new EventLoopExtension();

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/", (ctx, req) -> HttpResponse.of(200));
        }
    };

    @Test
    void basicCase() throws Exception {
        class SimpleService {

            @Post("/foo")
            @ProducesJson
            public Foo foo(Foo foo) {
                return foo;
            }
        }

        server.server().reconfigure(sb -> {
            sb.decorator(LoggingService.builder().newDecorator());
            sb.decorator(server.requestContextCaptor().newDecorator());
            sb.annotatedService("/", new SimpleService());
        });

        final Foo foo = new Foo();
        // foo2 does not produce logs
        final ResponseEntity<Foo> res = server.restClient()
                                              .post("/foo")
                                              .contentJson(foo)
                                              .execute(Foo.class)
                                              .join();
        assertThat(res.status().code()).isEqualTo(200);
        assertThat(res.content().hello).isEqualTo(foo.hello);

        final ServiceRequestContext sctx = server.requestContextCaptor().take();
        final Object reqContent = sctx.log().whenComplete().join().requestContent();
        assertThat(reqContent).isInstanceOf(AnnotatedRequest.class);
        final AnnotatedRequest annotatedReq = (AnnotatedRequest) reqContent;
        assertThat(annotatedReq.rawParameters()).containsExactly(foo);
        final Object resContent = sctx.log().whenComplete().join().responseContent();
        assertThat(resContent).isInstanceOf(AnnotatedResponse.class);
        final AnnotatedResponse annotatedRes = (AnnotatedResponse) resContent;
        assertThat(annotatedRes.rawValue()).isEqualTo(foo);
    }

    @Test
    void multipleInputs() throws Exception {
        class SimpleService {
            @Post
            @ProducesJson
            public Foo foo(Foo foo, @Param String param, HttpRequest req,
                           AggregatedHttpRequest areq, ServiceRequestContext sctx,
                           @Nullable @Param String nullParam) {
                return foo;
            }
        }

        server.server().reconfigure(sb -> {
            sb.decorator(LoggingService.newDecorator());
            sb.decorator(server.requestContextCaptor().newDecorator());
            sb.annotatedService("/foo", new SimpleService());
        });

        final Foo foo = new Foo();
        final ResponseEntity<Foo> res;
        res = server.restClient()
                    .post("/foo")
                    .queryParam("param", "paramValue")
                    .contentJson(foo)
                    .execute(Foo.class)
                    .join();
        assertThat(res.status().code()).isEqualTo(200);
        assertThat(res.content().hello).isEqualTo(foo.hello);

        final ServiceRequestContext sctx = server.requestContextCaptor().take();

        final Object reqContent = sctx.log().whenComplete().join().requestContent();
        assertThat(reqContent).isInstanceOf(AnnotatedRequest.class);
        final AnnotatedRequest annotatedRequest = (AnnotatedRequest) reqContent;
        assertThat(annotatedRequest.rawParameters()).hasSize(6);
        assertThat(annotatedRequest.rawParameters().get(0)).isEqualTo(foo);
        assertThat(annotatedRequest.rawParameters().get(1)).isEqualTo("paramValue");
        assertThat(annotatedRequest.rawParameters().get(2)).isInstanceOf(HttpRequest.class);
        assertThat(annotatedRequest.rawParameters().get(3)).isInstanceOf(AggregatedHttpRequest.class);
        assertThat(annotatedRequest.rawParameters().get(4)).isSameAs(sctx);
        assertThat(annotatedRequest.rawParameters().get(5)).isNull();

        final Object resContent = sctx.log().whenComplete().join().responseContent();
        assertThat(resContent).isInstanceOf(AnnotatedResponse.class);
        final AnnotatedResponse annotatedResponse = (AnnotatedResponse) resContent;
        assertThat(annotatedResponse.rawValue()).isEqualTo(foo);
    }

    @Test
    void fileTypeParameters() throws Exception {
        class SimpleService {
            @Post
            public HttpResponse fileReq(@Param File file1, @Param java.nio.file.Path path1,
                                        MultipartFile multipartFile1,
                                        @Param MultipartFile multipartFile2,
                                        @Param String param1) {
                return HttpResponse.of(param1);
            }
        }

        server.server().reconfigure(sb -> {
            sb.decorator(LoggingService.newDecorator());
            sb.decorator(server.requestContextCaptor().newDecorator());
            sb.annotatedService("/foo", new SimpleService());
        });

        final Multipart multipart = Multipart.of(
                BodyPart.of(ContentDisposition.of("form-data", "file1", "foo.txt"), "foo"),
                BodyPart.of(ContentDisposition.of("form-data", "path1", "bar.txt"), "bar"),
                BodyPart.of(ContentDisposition.of("form-data", "multipartFile1", "qux.txt"), "qux"),
                BodyPart.of(ContentDisposition.of("form-data", "multipartFile2", "quz.txt"),
                            MediaType.PLAIN_TEXT, "quz"),
                BodyPart.of(ContentDisposition.of("form-data", "param1"), "armeria")
        );
        final AggregatedHttpResponse response =
                server.blockingWebClient().execute(multipart.toHttpRequest("/foo"));
        assertThat(response.contentUtf8()).isEqualTo("armeria");

        final ServiceRequestContext sctx = server.requestContextCaptor().take();

        final Object reqContent = sctx.log().whenComplete().join().requestContent();
        assertThat(reqContent).isInstanceOf(AnnotatedRequest.class);
        final AnnotatedRequest annotatedRequest = (AnnotatedRequest) reqContent;
        assertThat(annotatedRequest.rawParameters()).hasSize(5);
        assertThat(annotatedRequest.rawParameters().get(0)).isInstanceOf(File.class);
        assertThat(annotatedRequest.rawParameters().get(1)).isInstanceOf(Path.class);
        assertThat(annotatedRequest.rawParameters().get(2)).isInstanceOf(MultipartFile.class);
        assertThat(annotatedRequest.rawParameters().get(3)).isInstanceOf(MultipartFile.class);
        assertThat(annotatedRequest.rawParameters().get(4)).isEqualTo("armeria");

        final Object resContent = sctx.log().whenComplete().join().responseContent();
        assertThat(resContent).isInstanceOf(AnnotatedResponse.class);
        final AnnotatedResponse annotatedResponse = (AnnotatedResponse) resContent;
        assertThat(annotatedResponse.rawValue()).isInstanceOf(HttpResponse.class);
    }

    @Test
    void fileTypeReturn() throws Exception {
        class SimpleService {
            @Get
            public HttpFile fileRes() {
                return HttpFile.of(HttpData.ofAscii("armeria"));
            }
        }

        server.server().reconfigure(sb -> {
            sb.decorator(LoggingService.newDecorator());
            sb.decorator(server.requestContextCaptor().newDecorator());
            sb.annotatedService("/foo", new SimpleService());
        });
        final AggregatedHttpResponse response = server.blockingWebClient().get("/foo");
        assertThat(response.contentUtf8()).isEqualTo("armeria");

        final ServiceRequestContext sctx = server.requestContextCaptor().take();

        final Object reqContent = sctx.log().whenComplete().join().requestContent();
        assertThat(reqContent).isInstanceOf(AnnotatedRequest.class);
        final AnnotatedRequest annotatedRequest = (AnnotatedRequest) reqContent;
        assertThat(annotatedRequest.rawParameters()).isEmpty();

        final Object resContent = sctx.log().whenComplete().join().responseContent();
        assertThat(resContent).isInstanceOf(AnnotatedResponse.class);
        final AnnotatedResponse annotatedResponse = (AnnotatedResponse) resContent;
        assertThat(annotatedResponse.rawValue()).isInstanceOf(HttpFile.class);
    }

    @Test
    void methodThrowsException() throws Exception {

        class SimpleService {
            @Post("/foo")
            @ProducesJson
            public Foo foo(Foo foo) {
                throw new RuntimeException();
            }
        }

        server.server().reconfigure(sb -> {
            sb.decorator(LoggingService.newDecorator());
            sb.decorator(server.requestContextCaptor().newDecorator());
            sb.annotatedService("/", new SimpleService());
        });

        final Foo foo = new Foo();
        // foo leaves request content
        final AggregatedHttpResponse res = server.blockingWebClient()
                                                 .prepare()
                                                 .post("/foo")
                                                 .contentJson(foo)
                                                 .execute();
        assertThat(res.status().code()).isEqualTo(500);

        final ServiceRequestContext sctx = server.requestContextCaptor().take();

        final Object reqContent = sctx.log().whenComplete().join().requestContent();
        assertThat(reqContent).isInstanceOf(AnnotatedRequest.class);
        final AnnotatedRequest annotatedRequest = (AnnotatedRequest) reqContent;
        assertThat(annotatedRequest.rawParameters()).containsExactly(foo);

        final Object resContent = sctx.log().whenComplete().join().responseContent();
        assertThat(resContent).isInstanceOf(AnnotatedResponse.class);
        final AnnotatedResponse annotatedResponse = (AnnotatedResponse) resContent;
        assertThat(annotatedResponse.rawValue()).isNull();
    }

    @SuppressWarnings("checkstyle:VisibilityModifier")
    static class Foo {

        public String hello = "hello";

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            final Foo foo = (Foo) o;
            return Objects.equal(hello, foo.hello);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(hello);
        }
    }
}
