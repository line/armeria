/*
 * Copyright 2020 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.internal.server.annotation;

import static com.google.common.base.MoreObjects.firstNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.annotation.Default;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Header;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class AnnotatedServiceNullableParamTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.annotatedService("/params", new Object() {
                @Get("/nullable")
                public String nullable(@Param @Nullable String value) {
                    return firstNonNull(value, "unspecified");
                }

                @SuppressWarnings("checkstyle:LegacyNullableAnnotation")
                @Get("/jsr305_nullable")
                public String jsr305Nullable(@Param @javax.annotation.Nullable String value) {
                    return firstNonNull(value, "unspecified");
                }

                @Get("/other_nullable")
                public String otherNullable(
                        @Param @io.micrometer.core.lang.Nullable String value) {
                    return nullable(value);
                }

                @Get("/default")
                public String defaultValue(@Param @Default("unspecified") String value) {
                    return value;
                }

                @Get("/optional")
                public String optional(@Param Optional<String> value) {
                    return value.orElse("unspecified");
                }
            });

            sb.annotatedService("/headers", new Object() {
                @Get("/nullable")
                public String nullable(@Header @Nullable String value) {
                    return firstNonNull(value, "unspecified");
                }

                @SuppressWarnings("checkstyle:LegacyNullableAnnotation")
                @Get("/jsr305_nullable")
                public String jsr305Nullable(
                        @Header @javax.annotation.Nullable String value) {
                    return nullable(value);
                }

                @Get("/other_nullable")
                public String otherNullable(
                        @Header @reactor.util.annotation.Nullable String value) {
                    return nullable(value);
                }

                @Get("/default")
                public String defaultValue(@Header @Default("unspecified") String value) {
                    return value;
                }

                @Get("/optional")
                public String optional(@Header Optional<String> value) {
                    return value.orElse("unspecified");
                }
            });
        }
    };

    @ParameterizedTest
    @CsvSource({ "/nullable", "/jsr305_nullable", "/other_nullable", "/default", "/optional" })
    void params(String path) {
        final WebClient client = WebClient.of(server.httpUri().resolve("/params"));
        assertThat(client.get(path + "?value=foo").aggregate().join().contentUtf8()).isEqualTo("foo");
        assertThat(client.get(path).aggregate().join().contentUtf8()).isEqualTo("unspecified");
    }

    @ParameterizedTest
    @CsvSource({ "/nullable", "/jsr305_nullable", "/other_nullable", "/default", "/optional" })
    void headers(String path) {
        final WebClient client = WebClient.of(server.httpUri().resolve("/headers"));
        assertThat(client.execute(RequestHeaders.of(HttpMethod.GET, path, "value", "foo"))
                         .aggregate().join().contentUtf8()).isEqualTo("foo");
        assertThat(client.get(path).aggregate().join().contentUtf8()).isEqualTo("unspecified");
    }
}
