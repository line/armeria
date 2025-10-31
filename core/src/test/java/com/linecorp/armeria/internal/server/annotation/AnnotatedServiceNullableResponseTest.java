package com.linecorp.armeria.internal.server.annotation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class AnnotatedServiceNullableResponseTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.annotatedService("/response", new Object() {
                @SuppressWarnings("checkstyle:LegacyNullableAnnotation")
                @Get("/jsr305_nullable")
                @javax.annotation.Nullable
                public String jsr305Nullable() {
                    return null;
                }

                @Get("/type_use_nullable")
                public @org.jspecify.annotations.Nullable String typeUseNullable() {
                    return null;
                }
            });
        }
    };

    @ParameterizedTest
    @CsvSource({
            "/jsr305_nullable", "/type_use_nullable"
    })
    void response(String path) {
        final BlockingWebClient client = BlockingWebClient.of(server.httpUri().resolve("/response"));
        assertThat(client.get(path).contentUtf8()).isEqualTo("");
    }
}
