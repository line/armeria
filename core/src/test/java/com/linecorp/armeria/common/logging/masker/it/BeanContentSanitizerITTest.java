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

package com.linecorp.armeria.common.logging.masker.it;

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.File;
import java.lang.reflect.ParameterizedType;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.hamcrest.core.StringContains;
import org.hamcrest.core.StringEndsWith;
import org.hamcrest.core.StringStartsWith;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.ContentDisposition;
import com.linecorp.armeria.common.Cookies;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseEntity;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.ContentSanitizer;
import com.linecorp.armeria.common.logging.FieldMasker;
import com.linecorp.armeria.common.logging.FieldMaskerSelector;
import com.linecorp.armeria.common.logging.MaskingStructs.Parent.SimpleFoo;
import com.linecorp.armeria.common.logging.MaskingStructs.Parent.SimpleFoo.InnerFoo.Masker;
import com.linecorp.armeria.common.multipart.BodyPart;
import com.linecorp.armeria.common.multipart.Multipart;
import com.linecorp.armeria.common.multipart.MultipartFile;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.ProducesJson;
import com.linecorp.armeria.server.annotation.RequestConverter;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;
import com.linecorp.armeria.server.annotation.ResponseConverter;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class BeanContentSanitizerITTest {

    private static final Pattern
            requestContentPattern = Pattern.compile(".*, content=(?<content>\\{\"params\":.*)}$");
    private static final Pattern
            responseContentPattern = Pattern.compile(".*, content=(?<content>\\{\"value\":.*)}$");

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/", (ctx, req) -> HttpResponse.of(200));
        }
    };

    @Test
    void basicCase() throws Exception {
        final ContentSanitizer<String> contentSanitizer = commonContentSanitizer();
        final TestLogWriter logWriter = new TestLogWriter(contentSanitizer);

        server.server().reconfigure(sb -> {
            sb.annotatedService()
              .decorator(LoggingService.builder().logWriter(logWriter).newDecorator())
              .build(new Object() {

                  @Post
                  @ProducesJson
                  public SimpleFoo hello(SimpleFoo foo) {
                      return foo;
                  }
              });
        });

        final SimpleFoo foo = new SimpleFoo();
        final ResponseEntity<SimpleFoo> res =
                server.restClient()
                      .post("/")
                      .contentJson(foo)
                      .execute(SimpleFoo.class)
                      .join();
        assertThat(res.status().code()).isEqualTo(200);
        assertThat(res.content()).isEqualTo(foo);

        await().untilAsserted(() -> assertThat(logWriter.blockingDeque()).hasSize(2));

        final String requestLog = logWriter.blockingDeque().takeFirst();
        Matcher matcher = requestContentPattern.matcher(requestLog);
        assertThat(matcher.find()).isTrue();
        final String requestContent = matcher.group("content");
        assertThatJson(requestContent).node("params[0].inner.hello").isEqualTo("world")
                                      .node("params[0].inner.masked").isEqualTo(null);

        final String responseLog = logWriter.blockingDeque().takeFirst();
        matcher = responseContentPattern.matcher(responseLog);
        assertThat(matcher.find()).isTrue();
        final String responseContent = matcher.group("content");
        assertThatJson(responseContent).node("value.inner.hello").isEqualTo("world")
                                       .node("value.inner.masked").isEqualTo(null);
    }

    @Test
    void returnValueMasked() throws Exception {
        final ContentSanitizer<String> contentSanitizer = commonContentSanitizer();
        final TestLogWriter logWriter = new TestLogWriter(contentSanitizer);

        server.server().reconfigure(sb -> {
            sb.annotatedService()
              .decorator(LoggingService.builder().logWriter(logWriter).newDecorator())
              .build(new Object() {

                  @Post
                  @ProducesJson
                  @Masker
                  public SimpleFoo hello(SimpleFoo foo) {
                      return foo;
                  }
              });
        });

        final SimpleFoo foo = new SimpleFoo();
        final ResponseEntity<SimpleFoo> res =
                server.restClient()
                      .post("/")
                      .contentJson(foo)
                      .execute(SimpleFoo.class)
                      .join();
        assertThat(res.status().code()).isEqualTo(200);
        assertThat(res.content()).isEqualTo(foo);

        await().untilAsserted(() -> assertThat(logWriter.blockingDeque()).hasSize(2));

        final String requestLog = logWriter.blockingDeque().takeFirst();
        Matcher matcher = requestContentPattern.matcher(requestLog);
        assertThat(matcher.find()).isTrue();
        final String requestContent = matcher.group("content");
        assertThatJson(requestContent).node("params[0].inner.hello").isEqualTo("world")
                                      .node("params[0].inner.masked").isEqualTo(null);

        final String responseLog = logWriter.blockingDeque().takeFirst();
        matcher = responseContentPattern.matcher(responseLog);
        assertThat(matcher.find()).isTrue();
        final String responseContent = matcher.group("content");
        assertThatJson(responseContent).node("value").isEqualTo(null);
    }

    @Test
    void complexCase() throws Exception {
        final ContentSanitizer<String> contentSanitizer = commonContentSanitizer();
        final TestLogWriter logWriter = new TestLogWriter(contentSanitizer);

        server.server().reconfigure(sb -> {
            sb.annotatedService()
              .decorator(LoggingService.builder().logWriter(logWriter).newDecorator())
              .build(new Object() {

                  @Post
                  @ProducesJson
                  public CompletableFuture<SimpleFoo> hello(SimpleFoo foo, @Param String param, HttpRequest req,
                                                            AggregatedHttpRequest areq,
                                                            ServiceRequestContext sctx,
                                                            @Nullable @Param String nullParam,
                                                            @Masker SimpleFoo maskedFoo,
                                                            @Masker @Param String maskedParam,
                                                            @Masker HttpRequest maskedReq,
                                                            @Masker AggregatedHttpRequest maskedAggReq,
                                                            @Masker ServiceRequestContext maskedCtx,
                                                            @Nullable @Param String maskedNullParam,
                                                            // handled automatically by Jdk8Module
                                                            @Param Optional<String> optionalParam,
                                                            QueryParams queryParams,
                                                            Cookies cookies,
                                                            HttpHeaders headers,
                                                            RequestHeaders requestHeaders) {
                      return CompletableFuture.supplyAsync(() -> {
                          try {
                              Thread.sleep(100);
                          } catch (InterruptedException e) {
                              // do nothing
                          }
                          return new SimpleFoo();
                      });
                  }
              });
        });

        final SimpleFoo foo = new SimpleFoo();
        final ResponseEntity<SimpleFoo> res =
                server.restClient()
                      .post("/")
                      .contentJson(foo)
                      .queryParam("param", "paramValue")
                      .queryParam("optionalParam", "optionalParamValue")
                      .queryParam("maskedParam", "maskedParamValue")
                      .execute(SimpleFoo.class)
                      .join();
        assertThat(res.status().code()).isEqualTo(200);
        assertThat(res.content()).isEqualTo(foo);

        await().untilAsserted(() -> assertThat(logWriter.blockingDeque()).hasSize(2));

        final String requestLog = logWriter.blockingDeque().takeFirst();
        Matcher matcher = requestContentPattern.matcher(requestLog);
        assertThat(matcher.find()).isTrue();
        final String requestContent = matcher.group("content");
        assertThatJson(requestContent).node("params[0].inner.hello").isEqualTo("world")
                                      .node("params[0].inner.masked").isEqualTo(null)
                                      .node("params[1]").isEqualTo("paramValue")
                                      .node("params[2]")
                                      .matches(new StringContains("AggregatingDecodedHttpRequest"))
                                      .node("params[3]")
                                      .matches(new StringStartsWith("DefaultAggregatedHttpRequest"))
                                      .node("params[4]")
                                      .matches(new StringStartsWith("[sreqId="))
                                      .node("params[5]").isEqualTo(null)
                                      .node("params[6]").isEqualTo(null)
                                      .node("params[7]").isEqualTo(null)
                                      .node("params[8]").isEqualTo(null)
                                      .node("params[9]").isEqualTo(null)
                                      .node("params[10]").isEqualTo(null)
                                      .node("params[11]").isEqualTo(null)
                                      .node("params[12]").isEqualTo("optionalParamValue")
                                      .node("params[13]").matches(new StringContains("param=paramValue"))
                                      .node("params[14]").isStringEqualTo("[]")
                                      .node("params[15].:method").isStringEqualTo("POST")
                                      .node("params[16].:method").isStringEqualTo("POST");

        final String responseLog = logWriter.blockingDeque().takeFirst();
        matcher = responseContentPattern.matcher(responseLog);
        assertThat(matcher.find()).isTrue();
        final String responseContent = matcher.group("content");
        assertThatJson(responseContent).node("value.inner.hello").isEqualTo("world")
                                       .node("value.inner.masked").isEqualTo(null);
    }

    @Test
    void files() throws Exception {
        final ContentSanitizer<String> contentSanitizer = commonContentSanitizer();
        final TestLogWriter logWriter = new TestLogWriter(contentSanitizer);

        server.server().reconfigure(sb -> {
            sb.annotatedService()
              .decorator(LoggingService.builder().logWriter(logWriter).newDecorator())
              .build(new Object() {

                  @Post
                  public HttpResponse hello(@Param File file1, @Param Path path1,
                                            MultipartFile multipartFile1,
                                            @Param MultipartFile multipartFile2,
                                            @Param String param1,
                                            Multipart multipart,
                                            @Masker @Param File maskedFile1,
                                            @Masker @Param Path maskedPath1,
                                            @Masker @Param MultipartFile maskedMultipartFile1,
                                            @Masker @Param String maskedParam1) {
                      return HttpResponse.of(param1);
                  }
              });
        });

        final Multipart multipart = Multipart.of(
                BodyPart.of(ContentDisposition.of("form-data", "file1", "foo.txt"), "foo"),
                BodyPart.of(ContentDisposition.of("form-data", "path1", "bar.txt"), "bar"),
                BodyPart.of(ContentDisposition.of("form-data", "multipartFile1", "qux.txt"), "qux"),
                BodyPart.of(ContentDisposition.of("form-data", "multipartFile2", "quz.txt"),
                            MediaType.PLAIN_TEXT, "quz"),
                BodyPart.of(ContentDisposition.of("form-data", "param1"), "armeria"),
                BodyPart.of(ContentDisposition.of("form-data", "maskedFile1", "foo.txt"), "foo"),
                BodyPart.of(ContentDisposition.of("form-data", "maskedPath1", "bar.txt"), "bar"),
                BodyPart.of(ContentDisposition.of("form-data", "maskedMultipartFile1", "qux.txt"), "qux"),
                BodyPart.of(ContentDisposition.of("form-data", "maskedParam1"), "armeria")
        );
        final AggregatedHttpResponse response =
                server.blockingWebClient().execute(multipart.toHttpRequest("/"));
        assertThat(response.contentUtf8()).isEqualTo("armeria");

        await().untilAsserted(() -> assertThat(logWriter.blockingDeque()).hasSize(2));

        final String requestLog = logWriter.blockingDeque().takeFirst();
        Matcher matcher = requestContentPattern.matcher(requestLog);
        assertThat(matcher.find()).isTrue();
        final String requestContent = matcher.group("content");
        assertThatJson(requestContent).node("params[0]").matches(new StringEndsWith(".multipart"))
                                      .node("params[1]").matches(new StringEndsWith(".multipart"))
                                      .node("params[2]").matches(new StringStartsWith("DefaultMultipartFile"))
                                      .node("params[3]").matches(new StringStartsWith("DefaultMultipartFile"))
                                      .node("params[4]").isEqualTo("armeria")
                                      .node("params[5]").matches(new StringStartsWith("DefaultMultipart"))
                                      .node("params[6]").isEqualTo(null)
                                      .node("params[7]").isEqualTo(null)
                                      .node("params[8]").isEqualTo(null)
                                      .node("params[9]").isEqualTo(null);

        final String responseLog = logWriter.blockingDeque().takeFirst();
        matcher = responseContentPattern.matcher(responseLog);
        assertThat(matcher.find()).isTrue();
        final String responseContent = matcher.group("content");
        assertThatJson(responseContent).node("value").matches(new StringContains("HttpResponse"));
    }

    @Test
    void exceptionThrown() throws Exception {
        final ContentSanitizer<String> contentSanitizer = commonContentSanitizer();
        final TestLogWriter logWriter = new TestLogWriter(contentSanitizer);

        server.server().reconfigure(sb -> {
            sb.annotatedService()
              .decorator(LoggingService.builder().logWriter(logWriter).newDecorator())
              .build(new Object() {

                  @Post
                  @ProducesJson
                  public SimpleFoo hello(SimpleFoo foo) {
                      throw new RuntimeException();
                  }
              });
        });

        final SimpleFoo foo = new SimpleFoo();
        final AggregatedHttpResponse res =
                server.blockingWebClient()
                      .prepare()
                      .post("/")
                      .contentJson(foo)
                      .execute();
        assertThat(res.status().code()).isEqualTo(500);

        await().untilAsserted(() -> assertThat(logWriter.blockingDeque()).hasSize(2));

        final String requestLog = logWriter.blockingDeque().takeFirst();
        Matcher matcher = requestContentPattern.matcher(requestLog);
        assertThat(matcher.find()).isTrue();
        final String requestContent = matcher.group("content");
        assertThatJson(requestContent).node("params[0].inner.hello").isEqualTo("world")
                                      .node("params[0].inner.masked").isEqualTo(null);

        final String responseLog = logWriter.blockingDeque().takeFirst();
        matcher = responseContentPattern.matcher(responseLog);
        assertThat(matcher.find()).isTrue();
        final String responseContent = matcher.group("content");
        assertThatJson(responseContent).node("value").isEqualTo(null);
    }

    static class UnserializableBean {
    }

    static class UnserializableBeanConverter implements RequestConverterFunction, ResponseConverterFunction {

        @Nullable
        @Override
        public Object convertRequest(ServiceRequestContext ctx, AggregatedHttpRequest request,
                                     Class<?> expectedResultType,
                                     @Nullable ParameterizedType expectedParameterizedResultType)
                throws Exception {
            return new UnserializableBean();
        }

        @Override
        public HttpResponse convertResponse(ServiceRequestContext ctx, ResponseHeaders headers,
                                            @Nullable Object result, HttpHeaders trailers)
                throws Exception {
            return HttpResponse.of("{}");
        }
    }

    @Test
    void unserializable() throws Exception {

        final ContentSanitizer<String> contentSanitizer = commonContentSanitizer();
        final TestLogWriter logWriter = new TestLogWriter(contentSanitizer);

        server.server().reconfigure(sb -> {
            sb.annotatedService()
              .decorator(LoggingService.builder().logWriter(logWriter).newDecorator())
              .build(new Object() {

                  @Post
                  @ProducesJson
                  @RequestConverter(UnserializableBeanConverter.class)
                  @ResponseConverter(UnserializableBeanConverter.class)
                  public UnserializableBean hello(UnserializableBean foo) {
                      return foo;
                  }
              });
        });

        final AggregatedHttpResponse res = server.blockingWebClient()
                                                 .prepare()
                                                 .post("/")
                                                 .content("{}")
                                                 .execute();
        assertThat(res.status().code()).isEqualTo(200);
        assertThat(res.contentUtf8()).isEqualTo("{}");

        await().untilAsserted(() -> assertThat(logWriter.blockingDeque()).hasSize(2));

        final String requestLog = logWriter.blockingDeque().takeFirst();
        Matcher matcher = requestContentPattern.matcher(requestLog);
        assertThat(matcher.find()).isTrue();
        final String requestContent = matcher.group("content");
        assertThatJson(requestContent).node("params[0]").isEqualTo("{}");

        final String responseLog = logWriter.blockingDeque().takeFirst();
        matcher = responseContentPattern.matcher(responseLog);
        assertThat(matcher.find()).isTrue();
        final String responseContent = matcher.group("content");
        assertThatJson(responseContent).node("value").isEqualTo("{}");
    }

    private static ContentSanitizer<String> commonContentSanitizer() {
        return ContentSanitizer.builder()
                               .fieldMaskerSelector(FieldMaskerSelector.ofBean(info -> {
                                   final Masker maskerAnn = info.getAnnotation(Masker.class);
                                   if (maskerAnn == null) {
                                       return FieldMasker.fallthrough();
                                   }
                                   return FieldMasker.nullify();
                               }))
                               .buildForText();
    }
}
