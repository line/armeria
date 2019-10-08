package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.annotation.Get;

class AnnotatedServiceBindingBuilderTest {

    @Test
    public void testWhenPathPrefixIsNotGivenThenUsesDefault() {
        final Server server = Server.builder()
                                    .annotatedService()
                                    .requestTimeout(Duration.ofMillis(5000))
                                    .exceptionHandler((ctx, request, cause) -> HttpResponse.of(400))
                                    .build(new TestService())
                                    .build();

        assertThat(server.config().serviceConfigs()).hasSize(1);
        final ServiceConfig serviceConfig = server.config().serviceConfigs().get(0);
        assertThat(serviceConfig.route().paths()).hasSize(1);
        assertThat(serviceConfig.route().paths().get(0)).isEqualTo("exact:/foo");

    }

    static class TestService {
        @Get("/foo")
        public HttpResponse foo() {
            return HttpResponse.of(200);
        }
    }

}