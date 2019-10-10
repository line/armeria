package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.logging.ContentPreviewer;
import com.linecorp.armeria.common.logging.ContentPreviewerFactory;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.logging.AccessLogWriter;
import com.linecorp.armeria.testing.junit.server.ServerExtension;

public class AnnotatedServiceBindingBuilderTest {

    public static final ExceptionHandlerFunction handlerFunction = (ctx, req, cause) -> HttpResponse.of(501);

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.annotatedService()
              .exceptionHandler(handlerFunction)
              .buildAnnotated(new TestService())
              .build();
        }
    };

    static void testStatusCode(CloseableHttpClient hc, HttpRequestBase req,
                               int statusCode) throws IOException {
        try (CloseableHttpResponse res = hc.execute(req)) {
            checkResult(res, statusCode);
        }
    }

    static void checkResult(org.apache.http.HttpResponse res,
                            int statusCode) throws IOException {
        final HttpStatus status = HttpStatus.valueOf(statusCode);
        assertThat(res.getStatusLine().toString()).isEqualTo(
                "HTTP/1.1 " + status);
    }

    static HttpRequestBase get(String uri) {
        return new HttpGet(server.httpUri(uri));
    }

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

    @Test
    void testServiceDecoration_shouldCatchException() throws IOException {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            testStatusCode(hc, get("/foo"), 501);
        }
    }

    static class TestService {
        @Get("/foo")
        public HttpResponse foo() {
            throw new RuntimeException();
        }
    }
}