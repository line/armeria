/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.armeria.spring.web.reactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import java.util.ArrayList;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.web.reactive.server.ReactiveWebServerFactory;
import org.springframework.boot.web.server.Compression;
import org.springframework.boot.web.server.Ssl;
import org.springframework.boot.web.server.WebServer;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.prometheus.PrometheusMeterRegistries;
import com.linecorp.armeria.internal.common.util.PortUtil;
import com.linecorp.armeria.internal.testing.MockAddressResolverGroup;
import com.linecorp.armeria.server.HttpStatusException;
import com.linecorp.armeria.server.ServerErrorHandler;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.healthcheck.HealthChecker;
import com.linecorp.armeria.spring.ArmeriaServerConfigurator;
import com.linecorp.armeria.spring.ArmeriaSettings;
import com.linecorp.armeria.spring.DocServiceConfigurator;
import com.linecorp.armeria.spring.HealthCheckServiceConfigurator;
import com.linecorp.armeria.spring.InternalServices;
import com.linecorp.armeria.spring.actuate.ArmeriaSpringActuatorAutoConfiguration;

import io.micrometer.core.instrument.MeterRegistry;
import reactor.core.publisher.Mono;

class ArmeriaReactiveWebServerFactoryTest {

    static final String POST_BODY = "Hello, world!";

    private final DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
    private static final ObjectMapper mapper = new ObjectMapper();
    private static ClientFactory clientFactory;

    private static ArmeriaReactiveWebServerFactory factory(ConfigurableListableBeanFactory beanFactory) {
        return new RetryableArmeriaReactiveWebServerFactory(beanFactory, new MockEnvironment());
    }

    private ArmeriaReactiveWebServerFactory factory() {
        return factory(beanFactory);
    }

    @BeforeAll
    static void beforeAll() {
        clientFactory =
                ClientFactory.builder()
                             .tlsNoVerify()
                             .addressResolverGroupFactory(group -> MockAddressResolverGroup.localhost())
                             .build();
    }

    @AfterAll
    static void afterAll() {
        clientFactory.closeAsync();
    }

    private static WebClient httpsClient(WebServer server) {
        return WebClient.builder("https://example.com:" + server.getPort())
                        .factory(clientFactory)
                        .build();
    }

    private static WebClient httpClient(WebServer server) {
        return WebClient.builder("http://example.com:" + server.getPort())
                        .factory(clientFactory)
                        .build();
    }

    private static class HelloService {
        @Get("/hello/:name")
        public String hello(@Param String name) {
            return "Hello, " + name;
        }
    }

    @Test
    void shouldRunOnSpecifiedPort() {
        // There is a race condition on finding an unused port.
        // The found port seems to be used by another test before using it because of the parallel test option.
        // So this test case is tried up to 3 times to avoid flakiness.
        for (int i = 0; i < 3; i++) {
            final ArmeriaReactiveWebServerFactory factory = factory(new DefaultListableBeanFactory());
            final int port = PortUtil.unusedTcpPort();
            factory.setPort(port);
            try {
                runEchoServer(factory, server -> assertThat(server.getPort()).isEqualTo(port));
            } catch (Throwable ex) {
                if (i < 2) {
                    continue;
                }
            }
        }
    }

    @Test
    void shouldReturnEchoResponse() {
        final ArmeriaReactiveWebServerFactory factory = factory();
        runEchoServer(factory, server -> {
            final WebClient client = httpClient(server);
            validateEchoResponse(sendPostRequest(client));

            final AggregatedHttpResponse res = client.get("/hello").aggregate().join();
            assertThat(res.status()).isEqualTo(com.linecorp.armeria.common.HttpStatus.OK);
            assertThat(res.contentUtf8()).isEmpty();
        });
    }

    @Test
    void shouldConfigureTlsWithSelfSignedCertificate() {
        final ArmeriaReactiveWebServerFactory factory = factory();
        final Ssl ssl = new Ssl();
        ssl.setEnabled(true);
        factory.setSsl(ssl);
        runEchoServer(factory, server -> validateEchoResponse(sendPostRequest(httpsClient(server))));
    }

    @Test
    void shouldReturnBadRequestDueToException() {
        final ArmeriaReactiveWebServerFactory factory = factory();
        runServer(factory, AlwaysFailureHandler.INSTANCE, server -> {
            final WebClient client = httpClient(server);

            final AggregatedHttpResponse res1 = client.post("/hello", "hello").aggregate().join();
            assertThat(res1.status()).isEqualTo(com.linecorp.armeria.common.HttpStatus.BAD_REQUEST);

            final AggregatedHttpResponse res2 = client.get("/hello").aggregate().join();
            assertThat(res2.status()).isEqualTo(com.linecorp.armeria.common.HttpStatus.BAD_REQUEST);
        });
    }

    @Test
    void shouldReturnCompressedResponse() {
        final ArmeriaReactiveWebServerFactory factory = factory();
        final Compression compression = new Compression();
        compression.setEnabled(true);
        compression.setMinResponseSize(DataSize.ofBytes(1));
        compression.setMimeTypes(new String[] { "text/plain" });
        compression.setExcludedUserAgents(new String[] { "unknown-agent/[0-9]+\\.[0-9]+\\.[0-9]+$" });
        factory.setCompression(compression);
        runEchoServer(factory, server -> {
            final AggregatedHttpResponse res = sendPostRequest(httpClient(server));
            assertThat(res.status()).isEqualTo(com.linecorp.armeria.common.HttpStatus.OK);
            assertThat(res.headers().get(HttpHeaderNames.CONTENT_ENCODING)).isEqualTo("gzip");
            assertThat(res.contentUtf8()).isNotEqualTo("hello");
        });
    }

    @Test
    void shouldReturnNonCompressedResponse_dueToContentType() {
        final ArmeriaReactiveWebServerFactory factory = factory();
        final Compression compression = new Compression();
        compression.setEnabled(true);
        compression.setMinResponseSize(DataSize.ofBytes(1));
        compression.setMimeTypes(new String[] { "text/html" });
        factory.setCompression(compression);
        runEchoServer(factory, server -> {
            final AggregatedHttpResponse res = sendPostRequest(httpClient(server));
            validateEchoResponse(res);
            assertThat(res.headers().get(HttpHeaderNames.CONTENT_ENCODING)).isNull();
        });
    }

    @Test
    void shouldReturnNonCompressedResponse_dueToUserAgent() {
        final ArmeriaReactiveWebServerFactory factory = factory();
        final Compression compression = new Compression();
        compression.setEnabled(true);
        compression.setMinResponseSize(DataSize.ofBytes(1));
        compression.setExcludedUserAgents(new String[] { "test-agent/[0-9]+\\.[0-9]+\\.[0-9]+$" });
        factory.setCompression(compression);
        runEchoServer(factory, server -> {
            final AggregatedHttpResponse res = sendPostRequest(httpClient(server));
            validateEchoResponse(res);
            assertThat(res.headers().get(HttpHeaderNames.CONTENT_ENCODING)).isNull();
        });
    }

    private static AggregatedHttpResponse sendPostRequest(WebClient client) {
        final RequestHeaders requestHeaders =
                RequestHeaders.of(HttpMethod.POST, "/hello",
                                  HttpHeaderNames.USER_AGENT, "test-agent/1.0.0",
                                  HttpHeaderNames.ACCEPT_ENCODING, "gzip");
        return client.execute(requestHeaders, HttpData.wrap(POST_BODY.getBytes())).aggregate().join();
    }

    private static void validateEchoResponse(AggregatedHttpResponse res) {
        assertThat(res.status()).isEqualTo(com.linecorp.armeria.common.HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo(POST_BODY);
    }

    private static void runEchoServer(ReactiveWebServerFactory factory,
                                      Consumer<WebServer> validator) {
        runServer(factory, EchoHandler.INSTANCE, validator);
    }

    private static void runServer(ReactiveWebServerFactory factory,
                                  HttpHandler httpHandler,
                                  Consumer<WebServer> validator) {
        final WebServer server = factory.getWebServer(httpHandler);
        server.start();
        try {
            validator.accept(server);
        } finally {
            server.stop();
        }
    }

    private static void registerInternalServices(DefaultListableBeanFactory beanFactory) {
        final RootBeanDefinition rbd = new RootBeanDefinition(InternalServices.class, () ->
                InternalServices.of(
                        beanFactory.getBean(ArmeriaSettings.class),
                        beanFactory.getBeanProvider(MeterRegistry.class)
                                   .getIfAvailable(Flags::meterRegistry),
                        new ArrayList<>(beanFactory.getBeansOfType(HealthChecker.class).values()),
                        new ArrayList<>(
                                beanFactory.getBeansOfType(HealthCheckServiceConfigurator.class).values()),
                        new ArrayList<>(beanFactory.getBeansOfType(DocServiceConfigurator.class).values()),
                        null,
                        null,
                        false));
        beanFactory.registerBeanDefinition("internalServices", rbd);
    }

    static class EchoHandler implements HttpHandler {
        static final EchoHandler INSTANCE = new EchoHandler();

        @Override
        public Mono<Void> handle(ServerHttpRequest request, ServerHttpResponse response) {
            response.setStatusCode(HttpStatus.OK);
            response.getHeaders().add(HttpHeaderNames.CONTENT_TYPE.toString(),
                                      MediaType.PLAIN_TEXT_UTF_8.toString());
            return response.writeWith(request.getBody());
        }
    }

    static class AlwaysFailureHandler implements HttpHandler {
        static final AlwaysFailureHandler INSTANCE = new AlwaysFailureHandler();

        @Override
        public Mono<Void> handle(ServerHttpRequest request, ServerHttpResponse response) {
            response.setStatusCode(HttpStatus.OK);
            return request.getBody().map(data -> {
                // skip data, then throw an exception.
                throw HttpStatusException.of(com.linecorp.armeria.common.HttpStatus.BAD_REQUEST);
            }).doOnComplete(() -> {
                // An HTTP GET request doesn't have a body, so onComplete will be immediately called.
                throw HttpStatusException.of(com.linecorp.armeria.common.HttpStatus.BAD_REQUEST);
            }).then();
        }
    }

    @Test
    void testMultipleBeansRegistered_TooManyMeterRegistryBeans() {
        final ArmeriaReactiveWebServerFactory factory = factory();

        RootBeanDefinition rbd = new RootBeanDefinition(ArmeriaSettings.class);
        beanFactory.registerBeanDefinition("armeriaSettings", rbd);

        rbd = new RootBeanDefinition(MeterRegistry.class, PrometheusMeterRegistries::newRegistry);
        beanFactory.registerBeanDefinition("meterRegistry1", rbd);

        rbd = new RootBeanDefinition(MeterRegistry.class, PrometheusMeterRegistries::newRegistry);
        beanFactory.registerBeanDefinition("meterRegistry2", rbd);

        assertThatThrownBy(() -> runEchoServer(factory, server -> fail("Should never reach here")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void testMultipleBeansRegistered_shouldUsePrimaryBean() {
        final ArmeriaReactiveWebServerFactory factory = factory();

        RootBeanDefinition rbd = new RootBeanDefinition(ArmeriaSettings.class);
        beanFactory.registerBeanDefinition("armeriaSettings", rbd);

        rbd = new RootBeanDefinition(MeterRegistry.class, PrometheusMeterRegistries::newRegistry);
        beanFactory.registerBeanDefinition("meterRegistry1", rbd);

        rbd = new RootBeanDefinition(MeterRegistry.class, PrometheusMeterRegistries::newRegistry);
        rbd.setPrimary(true);
        beanFactory.registerBeanDefinition("meterRegistry2", rbd);

        registerInternalServices(beanFactory);

        runEchoServer(factory, server -> {
            final WebClient client = httpClient(server);
            validateEchoResponse(sendPostRequest(client));

            final AggregatedHttpResponse res = client.get("/hello").aggregate().join();
            assertThat(res.status()).isEqualTo(com.linecorp.armeria.common.HttpStatus.OK);
            assertThat(res.contentUtf8()).isEmpty();
        });
    }

    @Test
    void testDocServiceConfigurator_withDocServiceConfigurator() {
        final ArmeriaReactiveWebServerFactory factory = factory();

        RootBeanDefinition rbd = new RootBeanDefinition(ArmeriaSettings.class);
        beanFactory.registerBeanDefinition("armeriaSettings", rbd);

        rbd = new RootBeanDefinition(ArmeriaServerConfigurator.class,
                                     () -> builder -> builder.annotatedService()
                                                             .build(new HelloService()));
        beanFactory.registerBeanDefinition("armeriaServerConfigurator", rbd);

        rbd = new RootBeanDefinition(DocServiceConfigurator.class,
                                     () -> builder -> builder.examplePaths(
                                                                     HelloService.class,
                                                                     "hello",
                                                                     "/hello/foo")
                                                             .build());
        beanFactory.registerBeanDefinition("docServiceConfigurator", rbd);

        registerInternalServices(beanFactory);

        runEchoServer(factory, server -> {
            final WebClient client = httpClient(server);
            final AggregatedHttpResponse res = client.get("/internal/docs/specification.json")
                                                     .aggregate()
                                                     .join();
            assertThat(res.status()).isEqualTo(com.linecorp.armeria.common.HttpStatus.OK);

            try {
                final JsonNode actualJson = mapper.readTree(res.contentUtf8());
                assertThat(actualJson.path("services")
                                     .path(0)
                                     .path("methods")
                                     .path(0)
                                     .path("examplePaths")
                                     .path(0)
                                     .textValue())
                        .isEqualTo("/hello/foo");
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    void testDocServiceConfigurator_withoutDocServiceConfigurator() {
        final ArmeriaReactiveWebServerFactory factory = factory();

        RootBeanDefinition rbd = new RootBeanDefinition(ArmeriaSettings.class);
        beanFactory.registerBeanDefinition("armeriaSettings", rbd);

        rbd = new RootBeanDefinition(ArmeriaServerConfigurator.class,
                                     () -> builder -> builder.annotatedService()
                                                             .build(new HelloService()));
        beanFactory.registerBeanDefinition("armeriaServerConfigurator", rbd);

        registerInternalServices(beanFactory);

        runEchoServer(factory, server -> {
            final WebClient client = httpClient(server);
            final AggregatedHttpResponse res = client.get("/internal/docs/specification.json")
                                                     .aggregate()
                                                     .join();
            assertThat(res.status()).isEqualTo(com.linecorp.armeria.common.HttpStatus.OK);
            try {
                final JsonNode actualJson = mapper.readTree(res.contentUtf8());
                assertThat(actualJson.path("services")
                                     .path(0)
                                     .path("methods")
                                     .path(0)
                                     .path("examplePaths")
                                     .path(0)
                                     .textValue())
                        .isNullOrEmpty();
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    void testHealthCheckServiceConfigurator() {
        final ArmeriaReactiveWebServerFactory factory = factory();

        RootBeanDefinition rbd = new RootBeanDefinition(ArmeriaSettings.class);
        beanFactory.registerBeanDefinition("armeriaSettings", rbd);

        rbd = new RootBeanDefinition(HealthCheckServiceConfigurator.class,
                                     () -> builder -> builder.updatable(true));
        beanFactory.registerBeanDefinition("healthCheckServiceConfigurator", rbd);

        registerInternalServices(beanFactory);

        runEchoServer(factory, server -> {
            final WebClient client = httpClient(server);

            AggregatedHttpResponse res = client.get("/internal/healthcheck").aggregate().join();
            assertThat(res.status()).isEqualTo(com.linecorp.armeria.common.HttpStatus.OK);

            res = client.post("/internal/healthcheck", "{\"healthy\":false}").aggregate().join();
            assertThat(res.status()).isEqualTo(com.linecorp.armeria.common.HttpStatus.SERVICE_UNAVAILABLE);
        });
    }

    @ParameterizedTest
    @CsvSource({
            "8080, 8080, true",
            "8080, , true",
            ", 8080, true",
            ", 8081, true",
            ", , true",
            "18080, 8080, false",
            "18080, , false",
            "0, 8080, false",
            "1, 8080, false",
            "65535, 8080, false",
    })
    void isManagementPortEqualsToServerPort(String managementPort, String serverPort,
                                            boolean expected) {
        final MockEnvironment environment = new MockEnvironment();
        if (!Strings.isNullOrEmpty(managementPort)) {
            environment.setProperty("management.server.port", managementPort);
        }
        if (!Strings.isNullOrEmpty(serverPort)) {
            environment.setProperty("server.port", serverPort);
        }
        final ArmeriaReactiveWebServerFactory factory = new ArmeriaReactiveWebServerFactory(beanFactory,
                                                                                            environment);
        assertThat(factory.isManagementPortEqualsToServerPort()).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "-1",
            "65536",
    })
    void isManagementPortEqualsToServerPortThrows(String managementPort) {
        assertThatThrownBy(() -> {
            final ArmeriaReactiveWebServerFactory factory = new ArmeriaReactiveWebServerFactory(
                    beanFactory, new MockEnvironment().withProperty("management.server.port", managementPort));
            factory.isManagementPortEqualsToServerPort();
        }).isInstanceOf(IllegalArgumentException.class);
    }

    @SpringBootApplication
    @RestController
    static class ArmeriaReactiveWebServerFactoryWithManagementServerPortTestConfiguration {

        @Bean
        public ArmeriaServerConfigurator armeriaServerConfigurator() {
            return builder -> builder.annotatedService()
                                     .build(new HelloService());
        }

        @Bean
        public DocServiceConfigurator docServiceConfigurator() {
            return builder -> builder.examplePaths(HelloService.class, "hello", "/hello/foo")
                                     .build();
        }

        @GetMapping("/webflux")
        Mono<String> hello() {
            return Mono.just("Hello, WebFlux!");
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = WebEnvironment.DEFINED_PORT,
            classes = ArmeriaReactiveWebServerFactoryWithManagementServerPortTestConfiguration.class,
            properties = "management.server.port=18080")
    @EnableAutoConfiguration
    @ImportAutoConfiguration(ArmeriaSpringActuatorAutoConfiguration.class)
    class ArmeriaReactiveWebServerFactoryWithManagementServerPortTest {

        private static final int SERVER_PORT = 8080;
        private static final int MANAGEMENT_PORT = 18080;

        @Test
        void testServerPort() {
            final WebClient client = WebClient.builder("http://127.0.0.1:" + SERVER_PORT)
                                              .factory(clientFactory)
                                              .build();

            // Request to Armeria service
            final AggregatedHttpResponse res1 = client.get("/hello/world")
                                                      .aggregate()
                                                      .join();
            assertThat(res1.status()).isEqualTo(com.linecorp.armeria.common.HttpStatus.OK);

            // Request to WebFlux controller
            final AggregatedHttpResponse res2 = client.get("/webflux")
                                                      .aggregate()
                                                      .join();
            assertThat(res2.status()).isEqualTo(com.linecorp.armeria.common.HttpStatus.OK);
        }

        @Test
        void testManagementPort() throws JsonProcessingException {
            final WebClient client = WebClient.builder("http://127.0.0.1:" + MANAGEMENT_PORT)
                                              .factory(clientFactory)
                                              .build();
            final AggregatedHttpResponse res = client.get("/internal/docs/specification.json")
                                                     .aggregate()
                                                     .join();
            assertThat(res.status()).isEqualTo(com.linecorp.armeria.common.HttpStatus.OK);

            final JsonNode actualJson = mapper.readTree(res.contentUtf8());
            assertThat(actualJson.path("services")
                                 .path(0)
                                 .path("methods")
                                 .path(0)
                                 .path("examplePaths")
                                 .path(0)
                                 .textValue())
                    .isEqualTo("/hello/foo");
        }
    }

    @Test
    void testServerErrorHandlerRegistration() {
        beanFactory.registerBeanDefinition("armeriaSettings", new RootBeanDefinition(ArmeriaSettings.class));
        registerInternalServices(beanFactory);

        // Add ServerErrorHandler @Bean which handles all exceptions and returns 200 with empty string content.
        final ServerErrorHandler handler = (ctx, req) -> HttpResponse.of("");
        final BeanDefinition rbd2 = new RootBeanDefinition(ServerErrorHandler.class, () -> handler);
        beanFactory.registerBeanDefinition("serverErrorHandler", rbd2);

        final ArmeriaReactiveWebServerFactory factory = factory();
        runServer(factory, (req, res) -> {
            throw new IllegalArgumentException(); // Always raise exception handler
        }, server -> {
            final WebClient client = httpClient(server);
            final AggregatedHttpResponse res1 = client.post("/hello", "hello").aggregate().join();
            assertThat(res1.status()).isEqualTo(com.linecorp.armeria.common.HttpStatus.OK);
        });
    }
}
