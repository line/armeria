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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import org.springframework.web.util.DefaultUriBuilderFactory;
import org.springframework.web.util.UriBuilderFactory;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseBuilder;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.internal.testing.MockAddressResolverGroup;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
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

    @Test
    void greetingWithRequestAttribute() {
        final AtomicReference<ClientRequestContext> ctxRef = new AtomicReference<>();

        final WebClient webClient =
                WebClient.builder(server.httpUri())
                         .decorator((delegate, ctx, req) -> {
                             ctxRef.set(ctx);
                             return delegate.execute(ctx, req);
                         })
                         .build();

        prepareResponse(response -> response.status(HttpStatus.OK)
                                            .header("Content-Type", "text/plain")
                                            .content("Hello Spring!"));

        StepVerifier.create(initService(webClient).getGreetingWithAttribute("myAttributeValue"))
                    .expectNext("Hello Spring!")
                    .expectComplete()
                    .verify(Duration.ofSeconds(5));

        assertThat(RequestAttributeAccess.get(ctxRef.get(), "myAttribute"))
                .isEqualTo("myAttributeValue");
    }

    @Test
    void greetingWithoutMethod() {
        prepareResponse(response -> response.status(HttpStatus.OK)
                                            .header("Content-Type", "text/plain")
                                            .content("Hello Spring!"));

        assertThatThrownBy(() -> initService().greetingWithoutMethod())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTP method is undefined");
    }

    @ValueSource(strings = { "", "/", "/foo", "/foo/bar" })
    @ParameterizedTest
    void greetingWithPrefix(String prefix) {
        prepareResponse(response -> response.status(HttpStatus.OK)
                                            .header("Content-Type", "text/plain")
                                            .content("Hello Spring!"));
        final WebClient client = WebClient.of(server.httpUri().resolve(prefix));
        StepVerifier.create(initService(client).getGreeting())
                    .expectNext("Hello Spring!")
                    .expectComplete()
                    .verify(Duration.ofSeconds(5));

        String expectedPath = "/greeting";
        if (!"/".equals(prefix)) {
            expectedPath = prefix + expectedPath;
        }
        assertThat(server.takeRequest().context().path()).isEqualTo(expectedPath);
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "/", "/foo", "/foo/bar" })
    void greetingWithEndpointGroup(String prefix) throws InterruptedException {
        prepareResponse(response -> response.status(HttpStatus.OK)
                                            .header("Content-Type", "text/plain")
                                            .content("Hello Spring!"));

        final SettableEndpointGroup endpointGroup = new SettableEndpointGroup();
        final WebClient client;
        if (!prefix.isEmpty()) {
            client = WebClient.of(SessionProtocol.HTTP, endpointGroup, prefix);
        } else {
            client = WebClient.of(SessionProtocol.HTTP, endpointGroup);
        }

        final Service service = initService(client);
        final Mono<String> greeting = service.getGreeting();
        // Lazily add the endpoint after the request has been made.
        endpointGroup.add(server.httpEndpoint());
        StepVerifier.create(greeting)
                    .expectNext("Hello Spring!")
                    .expectComplete()
                    .verify(Duration.ofSeconds(5));
        final ServiceRequestContext ctx = server.requestContextCaptor().take();
        if (prefix.isEmpty() || "/".equals(prefix)) {
            assertThat(ctx.path()).isEqualTo("/greeting");
        } else {
            assertThat(ctx.path()).isEqualTo(prefix + "/greeting");
        }
    }

    @Test
    void greetingWithAbsoluteUri() {
        // A pre-defined port should be used for testing the absolute URI.
        final int serverPort = 65493;
        final Server server = Server.builder()
                                    .http(serverPort)
                                    .service("/greeting", (ctx, req) -> HttpResponse.of("Hello Spring!"))
                                    .build();

        // Try to start the server and wait until it is ready to avoid port conflicts.
        await().untilAsserted(() -> {
            assertThatCode(() -> server.start().join())
                    .doesNotThrowAnyException();
        });

        try (ClientFactory factory =
                     ClientFactory.builder()
                                  .addressResolverGroupFactory(eventLoopGroup -> {
                                      return MockAddressResolverGroup.localhost();
                                  }).build()) {
            final WebClient nonBaseUriClient =
                    WebClient.builder()
                             .factory(factory)
                             .build();
            StepVerifier.create(initService(nonBaseUriClient).getGreetingAbsoluteUri())
                        .expectNext("Hello Spring!")
                        .expectComplete()
                        .verify(Duration.ofSeconds(5));
        }
        server.closeAsync();
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

    @ValueSource(strings = { "/", "/foo", "/foo/bar" })
    @ParameterizedTest
    void uriBuilderFactory(String prefix) throws Exception {
        final String ignoredResponseBody = "hello";
        prepareResponse(response -> response.status(200).content(ignoredResponseBody));
        final UriBuilderFactory factory = new DefaultUriBuilderFactory(
                anotherServer.httpUri().resolve(prefix).toString());

        final String responseBody = "Hello Spring 2!";
        prepareAnotherResponse(response -> response.ok()
                                                   .header("Content-Type", "text/plain")
                                                   .content(responseBody));

        // Unlike the original test, we need a non-baseURI client to use a custom UriBuilderFactory.
        final String actualBody = initService(WebClient.of()).getWithUriBuilderFactory(factory);

        assertThat(actualBody).isEqualTo(responseBody);
        String expectedPath = "/greeting";
        if (!"/".equals(prefix)) {
            expectedPath = prefix + expectedPath;
        }
        assertThat(anotherServer.takeRequest().context().path()).isEqualTo(expectedPath);
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
        final UriBuilderFactory factory = new DefaultUriBuilderFactory(
                anotherServer.httpUri().resolve("/").toString());

        final String actualBody = initService().getWithIgnoredUriBuilderFactory(dynamicUri, factory);

        assertThat(actualBody).isEqualTo(expectedResponseBody);
        assertThat(server.takeRequest().request().uri()).isEqualTo(dynamicUri);
        assertThat(server.takeRequest(1, TimeUnit.SECONDS)).isNull();
    }

    @Test
    void testStatusHandler() {
        final ArmeriaHttpExchangeAdapter adapter =
                ArmeriaHttpExchangeAdapter
                        .builder(server.webClient())
                        .statusHandler(
                                StatusHandler.of(HttpStatus.BAD_REQUEST,
                                                 ctx -> new IllegalArgumentException("bad input")))
                        .statusHandler((ctx, status) -> {
                            if (status.isServerError()) {
                                return new IllegalStateException("server error");
                            }
                            return null;
                        }).statusHandler((ctx, status) -> {
                            if (!status.isSuccess()) {
                                return new RuntimeException("unexpected status");
                            }
                            return null;
                        })
                        .build();

        final Service service =
                HttpServiceProxyFactory.builderFor(adapter)
                                       .build()
                                       .createClient(Service.class);

        prepareResponse(response -> response.status(HttpStatus.BAD_REQUEST)
                                            .header("Content-Type", "text/plain")
                                            .content("Bad request"));
        StepVerifier.create(service.getGreeting())
                    .expectErrorSatisfies(throwable -> {
                        assertThat(throwable).isInstanceOf(IllegalArgumentException.class)
                                             .hasMessage("bad input");
                    })
                    .verify(Duration.ofSeconds(5));

        prepareResponse(response -> response.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                            .header("Content-Type", "text/plain")
                                            .content("Internal server error"));
        StepVerifier.create(service.getGreeting())
                    .expectErrorSatisfies(throwable -> {
                        assertThat(throwable).isInstanceOf(IllegalStateException.class)
                                             .hasMessage("server error");
                    })
                    .verify(Duration.ofSeconds(5));

        prepareResponse(response -> response.status(HttpStatus.NOT_FOUND)
                                            .header("Content-Type", "text/plain")
                                            .content("Not found"));
        StepVerifier.create(service.getGreeting())
                    .expectErrorSatisfies(throwable -> {
                        assertThat(throwable).isInstanceOf(RuntimeException.class)
                                             .hasMessage("unexpected status");
                    })
                    .verify(Duration.ofSeconds(5));
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

        @HttpExchange("/greeting")
        Mono<String> greetingWithoutMethod();

        @GetExchange("/greeting")
        Mono<String> getGreeting();

        // A pre-defined port should be used for testing the absolute URI.
        @GetExchange("http://foo.com:65493/greeting")
        Mono<String> getGreetingAbsoluteUri();

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

    private static class SettableEndpointGroup extends DynamicEndpointGroup {

        SettableEndpointGroup() {
            super(EndpointSelectionStrategy.roundRobin());
        }

        void add(Endpoint endpoint) {
            addEndpoint(endpoint);
        }
    }
}
