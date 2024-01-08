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
/*
 * Copyright 2002-2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package com.linecorp.armeria.spring.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.http.MediaType;
import org.springframework.lang.Nullable;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PostExchange;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import org.springframework.web.util.DefaultUriBuilderFactory;
import org.springframework.web.util.UriBuilderFactory;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseBuilder;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.mock.MockWebServerExtension;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ArmeriaHttpExchangeAdapterTest {

    // Forked from https://github.com/spring-projects/spring-framework/blob/02e32baa804636116d0e010442324139cf6c1092/spring-webflux/src/test/java/org/springframework/web/reactive/function/client/support/WebClientAdapterTests.java

    @RegisterExtension
    static MockWebServerExtension server = new MockWebServerExtension();

    @RegisterExtension
    static MockWebServerExtension anotherServer = new MockWebServerExtension() {
        @Override
        protected void configureServer(ServerBuilder sb) throws Exception {
            sb.decorator(LoggingService.newDecorator());
        }
    };

    @Test
    void greeting() {
        prepareResponse(response -> response.status(HttpStatus.OK)
                                            .header("Content-Type", "text/plain")
                                            .content("Hello Spring!"));

        StepVerifier.create(initService().getGreeting())
                    .expectNext("Hello Spring!")
                    .expectComplete()
                    .verify(Duration.ofSeconds(5));
    }

    // gh-29624
    @Test
    void uri() throws Exception {
        final String expectedBody = "hello";
        prepareResponse(response -> response.status(200).content(expectedBody));

        final URI dynamicUri = server.httpUri().resolve("/greeting/123");
        final String actualBody = initService().getGreetingById(dynamicUri, "456");

        assertThat(actualBody).isEqualTo(expectedBody);
        assertThat(server.takeRequest().context().uri()).isEqualTo(dynamicUri);
    }

    @Test
    void formData() throws Exception {
        prepareResponse(response -> response.status(201));

        final MultiValueMap<String, String> map = new LinkedMultiValueMap<>();
        map.add("param1", "value 1");
        map.add("param2", "value 2");

        initService().postForm(map);

        final AggregatedHttpRequest request = server.takeRequest().request();
        assertThat(request.headers().get("Content-Type"))
                .isEqualTo("application/x-www-form-urlencoded;charset=UTF-8");
        assertThat(request.contentUtf8()).isEqualTo("param1=value+1&param2=value+2");
    }

    // gh-30342
    @Test
    void multipart() throws InterruptedException {
        prepareResponse(response -> response.status(201));
        final String fileName = "testFileName";
        final String originalFileName = "originalTestFileName";
        final MultipartFile file =
                new MockMultipartFile(fileName, originalFileName,
                                      MediaType.APPLICATION_JSON_VALUE, "test".getBytes());

        initService().postMultipart(file, "test2");

        final AggregatedHttpRequest request = server.takeRequest().request();
        assertThat(request.headers().get("Content-Type")).startsWith("multipart/form-data;boundary=");
        assertThat(request.contentUtf8())
                .containsSubsequence(
                        "Content-Disposition: form-data; name=\"file\"; filename=\"originalTestFileName\"",
                        "Content-Type: application/json", "Content-Length: 4", "test",
                        "Content-Disposition: form-data; name=\"anotherPart\"",
                        "Content-Type: text/plain;charset=UTF-8", "Content-Length: 5", "test2");
    }

    @Test
    void uriBuilderFactory() throws Exception {
        final String ignoredResponseBody = "hello";
        prepareResponse(response -> response.status(200).content(ignoredResponseBody));
        final UriBuilderFactory factory = new DefaultUriBuilderFactory(
                anotherServer.httpUri().resolve("/").toString());

        final String responseBody = "Hello Spring 2!";
        prepareAnotherResponse(response -> response.ok()
                                                   .header("Content-Type", "text/plain")
                                                   .content(responseBody));

        // Unlike the original test, we need a non-baseURI client to use a custom UriBuilderFactory.
        final String actualBody = initService(WebClient.of()).getWithUriBuilderFactory(factory);

        assertThat(actualBody).isEqualTo(responseBody);
        assertThat(anotherServer.takeRequest().context().path()).isEqualTo("/greeting");
        assertThat(server.takeRequest(1, TimeUnit.SECONDS)).isNull();
    }

    @Test
    void uriBuilderFactoryWithPathVariableAndRequestParam() throws Exception {
        final UriBuilderFactory factory = new DefaultUriBuilderFactory(
                anotherServer.httpUri().resolve("/").toString());

        final String responseBody = "Hello Spring over Armeria!";
        prepareAnotherResponse(response -> response.ok()
                                                   .header("Content-Type", "text/plain")
                                                   .content(responseBody));
        // Unlike the original test, we need a non-baseURI client to use a custom UriBuilderFactory.
        final String actualBody = initService(WebClient.of()).getWithUriBuilderFactory(factory, "123", "test");

        assertThat(actualBody).isEqualTo(responseBody);
        assertThat(anotherServer.takeRequest().request().path()).isEqualTo("/greeting/123?param=test");
        assertThat(server.takeRequest(1, TimeUnit.SECONDS)).isNull();
    }


    @Test
    void ignoredUriBuilderFactory() throws Exception {
        final String expectedResponseBody = "hello";
        prepareResponse(response -> response.status(200).content(expectedResponseBody));
        final URI dynamicUri = server.httpUri().resolve("/greeting/123");
        final UriBuilderFactory factory = new DefaultUriBuilderFactory(anotherServer.httpUri().resolve("/").toString());

        final String actualBody = initService().getWithIgnoredUriBuilderFactory(dynamicUri, factory);

        assertThat(actualBody).isEqualTo(expectedResponseBody);
        assertThat(server.takeRequest().request().uri()).isEqualTo(dynamicUri);
        assertThat(server.takeRequest(1, TimeUnit.SECONDS)).isNull();
    }

    private static void prepareResponse(Consumer<HttpResponseBuilder> consumer) {
        final HttpResponseBuilder builder = HttpResponse.builder();
        consumer.accept(builder);
        server.enqueue(builder.build());
    }

    private static void prepareAnotherResponse(Consumer<HttpResponseBuilder> consumer) {
        final HttpResponseBuilder builder = HttpResponse.builder();
        consumer.accept(builder);
        anotherServer.enqueue(builder.build());
    }

    private static Service initService() {
        return initService(server.webClient());
    }

    private static Service initService(WebClient webClient) {
        final ArmeriaHttpExchangeAdapter adapter = ArmeriaHttpExchangeAdapter.of(webClient);
        return HttpServiceProxyFactory.builderFor(adapter).build().createClient(Service.class);
    }

    private interface Service {

        @GetExchange("/greeting")
        Mono<String> getGreeting();

        @GetExchange("/greeting")
        Mono<String> getGreetingWithAttribute(@RequestAttribute String myAttribute);

        @GetExchange("/greetings/{id}")
        String getGreetingById(@Nullable URI uri, @PathVariable String id);

        @PostExchange(contentType = "application/x-www-form-urlencoded")
        void postForm(@RequestParam MultiValueMap<String, String> params);

        @PostExchange
        void postMultipart(MultipartFile file, @RequestPart String anotherPart);

        @GetExchange("/greeting")
        String getWithUriBuilderFactory(UriBuilderFactory uriBuilderFactory);

        @GetExchange("/greeting/{id}")
        String getWithUriBuilderFactory(UriBuilderFactory uriBuilderFactory,
                                        @PathVariable String id, @RequestParam String param);

        @GetExchange("/greeting")
        String getWithIgnoredUriBuilderFactory(URI uri, UriBuilderFactory uriBuilderFactory);

    }
}
