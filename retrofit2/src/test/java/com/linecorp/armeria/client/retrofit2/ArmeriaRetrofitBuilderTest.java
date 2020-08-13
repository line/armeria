/*
 * Copyright 2017 LINE Corporation
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
package com.linecorp.armeria.client.retrofit2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import retrofit2.Converter;
import retrofit2.Retrofit;
import retrofit2.converter.jackson.JacksonConverterFactory;
import retrofit2.http.GET;

class ArmeriaRetrofitBuilderTest {

    private static final Converter.Factory converterFactory =
            JacksonConverterFactory.create(new ObjectMapper());

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/secret", (ctx, req) -> {
                return HttpResponse.from(req.aggregate().thenApply(aggReq -> {
                    if ("Bearer: access-token".equals(aggReq.headers().get(HttpHeaderNames.AUTHORIZATION))) {
                        return HttpResponse.of("\"OK\"");
                    } else {
                        return HttpResponse.of(HttpStatus.FORBIDDEN);
                    }
                }));
            });
            sb.service("/slow", (ctx, req) ->
                    HttpResponse.delayed(HttpResponse.of("\"OK\""), Duration.ofSeconds(2)));
        }
    };

    @Test
    void build() {
        final Retrofit retrofit = ArmeriaRetrofit.of("http://example.com:8080/");
        assertThat(retrofit.baseUrl().toString()).isEqualTo("http://example.com:8080/");
    }

    @Test
    void build_withoutSlashAtEnd() {
        final Retrofit retrofit = ArmeriaRetrofit.of("http://example.com:8080");
        assertThat(retrofit.baseUrl().toString()).isEqualTo("http://example.com:8080/");
    }

    @Test
    void build_withNonRootPath() {
        assertThat(ArmeriaRetrofit.of("http://example.com:8080/a/b/c/").baseUrl().toString())
                .isEqualTo("http://example.com:8080/a/b/c/");
    }

    @Test
    void build_withNonRootPathNonSlashEnd() {
        assertThatThrownBy(() -> ArmeriaRetrofit.of("http://example.com:8080/a/b/c"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("baseUrl must end in /: http://example.com:8080/a/b/c");
    }

    @Test
    void build_moreSessionProtocol() {
        assertThat(ArmeriaRetrofit.of("h1c://example.com:8080/").baseUrl().toString())
                .isEqualTo("http://example.com:8080/");
        assertThat(ArmeriaRetrofit.of("h2c://example.com:8080/").baseUrl().toString())
                .isEqualTo("http://example.com:8080/");
        assertThat(ArmeriaRetrofit.of("h1://example.com:8080/").baseUrl().toString())
                .isEqualTo("https://example.com:8080/");
        assertThat(ArmeriaRetrofit.of("h2://example.com:8080/").baseUrl().toString())
                .isEqualTo("https://example.com:8080/");
        assertThat(ArmeriaRetrofit.of("https://example.com:8080/").baseUrl().toString())
                .isEqualTo("https://example.com:8080/");
    }

    @Test
    void build_armeriaGroupAuthority() {
        final Endpoint endpoint = Endpoint.of("127.0.0.1", 8080);
        final EndpointGroup group = EndpointGroup.of(endpoint, endpoint);

        assertThat(ArmeriaRetrofit.of(SessionProtocol.H2C, endpoint).baseUrl().toString())
                .isEqualTo("http://127.0.0.1:8080/");

        assertThat(ArmeriaRetrofit.of(SessionProtocol.H2, group).baseUrl().toString())
                .startsWith("https://armeria-group-");
    }

    @Test
    void build_clientOptions() {
        final Service secretService = ArmeriaRetrofit
                .builder(server.httpUri())
                .addHeader(HttpHeaderNames.AUTHORIZATION, "Bearer: access-token")
                .addConverterFactory(converterFactory)
                .build()
                .create(Service.class);
        assertThat(secretService.secret().join()).isEqualTo("OK");
    }

    @Test
    void build_overrideOption() {
        final WebClient client = WebClient.builder(server.httpUri())
                                          .responseTimeoutMillis(500L).build();
        assertThat(client.options().get(ClientOptions.RESPONSE_TIMEOUT_MILLIS).longValue()).isEqualTo(500);

        final Service serviceWithDefaultOptions = ArmeriaRetrofit.builder(client)
                                                                 .addConverterFactory(converterFactory)
                                                                 .build()
                                                                 .create(Service.class);
        assertThatThrownBy(() -> serviceWithDefaultOptions.slow().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IOException.class);

        final Service serviceWithCustomOptions =
                ArmeriaRetrofit.builder(client)
                               .option(ClientOptions.RESPONSE_TIMEOUT_MILLIS, 4000L)
                               .addConverterFactory(converterFactory)
                               .build()
                               .create(Service.class);
        assertThat(serviceWithCustomOptions.slow().join()).isEqualTo("OK");
    }

    interface Service {
        @GET("/secret")
        CompletableFuture<String> secret();

        @GET("/slow")
        CompletableFuture<String> slow();
    }
}
