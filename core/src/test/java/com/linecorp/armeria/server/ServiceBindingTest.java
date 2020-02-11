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

package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.testing.junit.server.ServerExtension;

class ServiceBindingTest {

    private static CountDownLatch accessLogWriterCheckLatch;
    private static CountDownLatch propertyCheckLatch;

    @RegisterExtension
    static final ServerExtension server = new ServerExtension(true) {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.route().get("/greet/{name}")
              .post("/greet")
              .produces(MediaType.PLAIN_TEXT_UTF_8)
              .requestTimeoutMillis(1000)
              .maxRequestLength(8192)
              .verboseResponses(true)
              .accessLogWriter(log -> accessLogWriterCheckLatch.countDown(), true)
              .decorator(delegate -> (ctx, req) -> {
                  ctx.log().whenComplete().thenAccept(log -> {
                      assertThat(ctx.requestTimeoutMillis()).isEqualTo(1000);
                      assertThat(ctx.maxRequestLength()).isEqualTo(8192);
                      assertThat(ctx.verboseResponses()).isTrue();

                      propertyCheckLatch.countDown();
                  });
                  return delegate.serve(ctx, req);
              })
              .build((ctx, req) -> {
                  if (req.method() == HttpMethod.GET) {
                      return HttpResponse.of(ctx.pathParam("name"));
                  }
                  if (req.method() == HttpMethod.POST) {
                      return HttpResponse.from(
                              req.aggregate().thenApply(request -> HttpResponse.of(request.contentUtf8())));
                  }
                  fail("Should never reach here");
                  return HttpResponse.of("Never reach here.");
              });

            sb.route().path("/hello")
              .methods(HttpMethod.POST)
              .consumes(MediaType.JSON, MediaType.PLAIN_TEXT_UTF_8)
              .produces(MediaType.JSON, MediaType.PLAIN_TEXT_UTF_8)
              .build((ctx, req) -> HttpResponse.from(
                      req.aggregate().thenApply(request -> {
                          final String resContent;
                          final MediaType contentType = req.contentType();
                          if (contentType == MediaType.JSON) {
                              resContent = "{\"name\":\"" + request.contentUtf8() + "\"}";
                          } else {
                              resContent = request.contentUtf8();
                          }
                          return HttpResponse.of(resContent);
                      })));
        }
    };

    @BeforeEach
    void setUp() {
        accessLogWriterCheckLatch = new CountDownLatch(1);
        propertyCheckLatch = new CountDownLatch(1);
    }

    @Test
    void routeService() throws InterruptedException {
        final WebClient client = WebClient.of(server.httpUri());
        AggregatedHttpResponse res = client.get("/greet/armeria").aggregate().join();
        propertyCheckLatch.await();
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("armeria");

        res = client.post("/greet", "armeria").aggregate().join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("armeria");

        res = client.put("/greet/armeria", "armeria").aggregate().join();
        assertThat(res.status()).isSameAs(HttpStatus.METHOD_NOT_ALLOWED);

        res = client.put("/greet", "armeria").aggregate().join();
        assertThat(res.status()).isSameAs(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Test
    void consumesAndProduces() throws IOException {
        final WebClient client = WebClient.of(server.httpUri());
        AggregatedHttpResponse res = client.execute(RequestHeaders.of(HttpMethod.POST, "/hello"), "armeria")
                                           .aggregate().join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("armeria");

        res = client.execute(RequestHeaders.of(HttpMethod.POST, "/hello",
                                               HttpHeaderNames.CONTENT_TYPE, MediaType.PLAIN_TEXT_UTF_8),
                             "armeria").aggregate().join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("armeria");

        res = client.execute(RequestHeaders.of(HttpMethod.POST, "/hello",
                                               HttpHeaderNames.CONTENT_TYPE, MediaType.JSON),
                             "armeria").aggregate().join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);

        final ObjectMapper mapper = new ObjectMapper();
        assertThat(mapper.readTree(res.contentUtf8()).get("name").textValue()).isEqualTo("armeria");

        res = client.execute(RequestHeaders.of(HttpMethod.POST, "/hello",
                                               HttpHeaderNames.CONTENT_TYPE, MediaType.PLAIN_TEXT_UTF_8,
                                               HttpHeaderNames.ACCEPT, MediaType.HTML_UTF_8),
                             "armeria").aggregate().join();
        assertThat(res.status()).isSameAs(HttpStatus.NOT_ACCEPTABLE);

        res = client.execute(RequestHeaders.of(HttpMethod.POST, "/hello",
                                               HttpHeaderNames.CONTENT_TYPE, MediaType.PLAIN_TEXT_UTF_8,
                                               HttpHeaderNames.ACCEPT, MediaType.PLAIN_TEXT_UTF_8),
                             "armeria").aggregate().join();
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("armeria");
    }

    @Test
    void accessLogWriter() throws InterruptedException {
        final WebClient client = WebClient.of(server.httpUri());
        client.execute(RequestHeaders.of(HttpMethod.POST, "/hello"), "armeria")
              .aggregate().join();

        assertThat(accessLogWriterCheckLatch.getCount()).isOne();

        client.get("/greet/armeria");
        accessLogWriterCheckLatch.await();
    }
}
