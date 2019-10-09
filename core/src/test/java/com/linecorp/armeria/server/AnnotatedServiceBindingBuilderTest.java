package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.logging.ContentPreviewer;
import com.linecorp.armeria.common.logging.ContentPreviewerFactory;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.logging.AccessLogWriter;

class AnnotatedServiceBindingBuilderTest {

    @Test
    void testWhenPathPrefixIsNotGivenThenUsesDefault() {
        final Server server = Server.builder()
                                    .annotatedService()
                                        .requestTimeout(Duration.ofMillis(5000))
                                        .exceptionHandler((ctx, request, cause) -> HttpResponse.of(400))
                                        .buildAnnotated(new TestService())
                                    .build();

        assertThat(server.config().serviceConfigs()).hasSize(1);
        final ServiceConfig serviceConfig = server.config().serviceConfigs().get(0);
        assertThat(serviceConfig.route().paths()).allMatch("/foo"::equals);
    }

    @Test
    void testWhenPathPrefixIsGivenThenItIsPrefixed() {
        final Server server = Server.builder()
                                    .annotatedService()
                                        .requestTimeout(Duration.ofMillis(5000))
                                        .exceptionHandler((ctx, request, cause) -> HttpResponse.of(400))
                                        .pathPrefix("/home")
                                    .buildAnnotated(new TestService())
                                    .build();

        assertThat(server.config().serviceConfigs()).hasSize(1);
        final ServiceConfig serviceConfig = server.config().serviceConfigs().get(0);
        assertThat(serviceConfig.route().paths()).allMatch("/home/foo"::equals);
    }


    @Test
    void testAllConfigurationsAreRespected() {
        final boolean verboseResponse = true;
        final boolean shutdownOnStop = true;
        final long maxRequestLength = 2 * 1024;
        final AccessLogWriter accessLogWriter = AccessLogWriter.common();
        final Duration requestTimeoutDuration = Duration.ofMillis(1000);
        final ContentPreviewerFactory factory = (ctx, headers) -> ContentPreviewer.ofText(1024);



        final Server server = Server.builder()
                                    .annotatedService()
                                    .requestTimeout(requestTimeoutDuration)
                                    .maxRequestLength(2 * 1024)
                                    .exceptionHandler((ctx, request, cause) -> HttpResponse.of(400))
                                    .pathPrefix("/home")
                                    .accessLogWriter(accessLogWriter, shutdownOnStop)
                                    .contentPreviewerFactory(factory)
                                    .verboseResponses(verboseResponse)
                                    .buildAnnotated(new TestService())
                                    .build();


        assertThat(server.config().serviceConfigs()).hasSize(1);
        final ServiceConfig serviceConfig = server.config().serviceConfigs().get(0);
        assertThat(serviceConfig.requestTimeoutMillis()).isEqualTo(requestTimeoutDuration.toMillis());
        assertThat(serviceConfig.maxRequestLength()).isEqualTo(maxRequestLength);
        assertThat(serviceConfig.accessLogWriter()).isEqualTo(accessLogWriter);
        assertThat(serviceConfig.shutdownAccessLogWriterOnStop()).isTrue();
        assertThat(serviceConfig.requestContentPreviewerFactory()).isEqualTo(factory);
        assertThat(serviceConfig.verboseResponses()).isTrue();
    }

    static class TestService {
        @Get("/foo")
        public HttpResponse foo() {
            return HttpResponse.of(200);
        }
    }


}