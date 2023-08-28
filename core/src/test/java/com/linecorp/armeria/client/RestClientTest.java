/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.reflections.ReflectionUtils;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.common.Cookie;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.internal.testing.GenerateNativeImageTrace;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Delete;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Header;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Patch;
import com.linecorp.armeria.server.annotation.Path;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.ProducesJson;
import com.linecorp.armeria.server.annotation.Put;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class RestClientTest {
    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.annotatedService(new Object() {
                @Get
                @Post
                @Put
                @Delete
                @Patch
                @ProducesJson
                @Path("/rest/{id}")
                public HttpResponse restApi(@Param String id, @Param String query,
                                            @Header("x-header") String header, String content,
                                            ServiceRequestContext ctx) {
                    final HttpRequest req = ctx.request();
                    final RestResponse restResponse =
                            new RestResponse(id,
                                             req.method().toString(),
                                             query,
                                             header,
                                             Iterables.getFirst(req.headers().cookies(), null).value(),
                                             content);
                    return HttpResponse.ofJson(restResponse);
                }
            });
        }
    };

    @ArgumentsSource(RestClientProvider.class)
    @ParameterizedTest
    @GenerateNativeImageTrace
    void restApi(RestClient restClient) {
        RestClientPreparation preparation = null;
        // HTTP methods used for REST APIs
        // See: https://restfulapi.net/http-methods/
        for (HttpMethod method : ImmutableList.of(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT,
                                                  HttpMethod.DELETE, HttpMethod.PATCH)) {
            switch (method) {
                case GET:
                    preparation = restClient.get("/rest/{id}");
                    break;
                case POST:
                    preparation = restClient.post("/rest/{id}");
                    break;
                case PUT:
                    preparation = restClient.put("/rest/{id}");
                    break;
                case PATCH:
                    preparation = restClient.patch("/rest/{id}");
                    break;
                case DELETE:
                    preparation = restClient.delete("/rest/{id}");
                    break;
            }
            assertThat(preparation).isNotNull();
            final RestResponse response =
                    preparation.content("content")
                               .header("x-header", "header-value")
                               .cookie(Cookie.ofSecure("cookie", "cookie-value"))
                               .pathParam("id", "1")
                               .queryParam("query", "query-value")
                               .execute(RestResponse.class)
                               .join()
                               .content();

            assertThat(response.getId()).isEqualTo("1");
            assertThat(response.getMethod()).isEqualTo(method.toString());
            assertThat(response.getQuery()).isEqualTo("query-value");
            assertThat(response.getHeader()).isEqualTo("header-value");
            assertThat(response.getCookie()).isEqualTo("cookie-value");
            assertThat(response.getContent()).isEqualTo("content");
        }
    }

    @Test
    void returnType_RestClientPreparation() throws NoSuchMethodException {
        for (final Method method : ReflectionUtils.getMethods(RequestPreparationSetters.class)) {
            if ("execute".equals(method.getName())) {
                continue;
            }
            final Method overridden =
                    RestClientPreparation.class.getMethod(method.getName(), method.getParameterTypes());
            assertThat(overridden.getReturnType())
                    .as("method : " + overridden)
                    .isEqualTo(RestClientPreparation.class);
        }
    }

    @Test
    void returnType_RestClientBuilder() throws NoSuchMethodException {
        for (final Method method : ReflectionUtils.getMethods(AbstractWebClientBuilder.class,
                                                              m -> Modifier.isPublic(m.getModifiers()))) {
            final Method overridden =
                    RestClientBuilder.class.getMethod(method.getName(), method.getParameterTypes());
            assertThat(overridden.getReturnType())
                    .as("method : " + overridden)
                    .isEqualTo(RestClientBuilder.class);
        }
    }

    private static class RestClientProvider implements ArgumentsProvider {

        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) throws Exception {
            return Stream.of(RestClient.of(server.httpUri()),
                             RestClient.of(server.webClient()),
                             RestClient.of("http://127.0.0.1:" + server.httpPort()),
                             server.webClient().asRestClient(),
                             RestClient.builder(server.httpUri())
                                       .decorator(LoggingClient.newDecorator())
                                       .build())
                         .map(Arguments::of);
        }
    }

    static final class RestResponse {
        private final String id;
        private final String method;
        private final String query;
        private final String header;
        private final String cookie;
        private final String content;

        @JsonCreator
        RestResponse(@JsonProperty("id") String id, @JsonProperty("method") String method,
                     @JsonProperty("query") String query, @JsonProperty("header") String header,
                     @JsonProperty("cookie") String cookie, @JsonProperty("content") String content) {
            this.id = id;
            this.method = method;
            this.query = query;
            this.header = header;
            this.cookie = cookie;
            this.content = content;
        }

        public String getId() {
            return id;
        }

        public String getMethod() {
            return method;
        }

        public String getQuery() {
            return query;
        }

        public String getHeader() {
            return header;
        }

        public String getCookie() {
            return cookie;
        }

        public String getContent() {
            return content;
        }
    }
}
