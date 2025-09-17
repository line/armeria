/*
 * Copyright 2016 LINE Corporation
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
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.HttpPreprocessor;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.testing.GenerateNativeImageTrace;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import okhttp3.HttpUrl;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Converter;
import retrofit2.Response;
import retrofit2.converter.jackson.JacksonConverterFactory;
import retrofit2.http.Body;
import retrofit2.http.Field;
import retrofit2.http.FormUrlEncoded;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.Headers;
import retrofit2.http.POST;
import retrofit2.http.Path;
import retrofit2.http.Query;
import retrofit2.http.Url;

@GenerateNativeImageTrace
class ArmeriaCallFactoryTest {

    public static class Pojo {
        @Nullable
        @JsonProperty("name")
        String name;
        @JsonProperty("age")
        int age;

        @JsonCreator
        Pojo(@JsonProperty("name") @Nullable String name, @JsonProperty("age") int age) {
            this.name = name;
            this.age = age;
        }

        @Override
        public boolean equals(@Nullable Object o) {
            if (o == this) {
                return true;
            }
            if (!(o instanceof Pojo)) {
                return false;
            }
            final Pojo other = (Pojo) o;
            return Objects.equals(name, other.name) && age == other.age;
        }

        @Override
        public int hashCode() {
            int result = 1;
            result = result * 31 + (name == null ? 43 : name.hashCode());
            result = result * 31 + age;
            return result;
        }

        @Override
        public String toString() {
            return "Pojo[name=" + name + ", age=" + age + ']';
        }
    }

    interface Service {

        @GET("/pojo")
        CompletableFuture<Pojo> pojo();

        @GET("/pojo")
        Call<Pojo> pojoReturnCall();

        @GET("/pojos")
        CompletableFuture<List<Pojo>> pojos();

        @GET("/queryString")
        CompletableFuture<Pojo> queryString(@Query("name") String name, @Query("age") int age);

        @GET("/queryString")
        CompletableFuture<Pojo> queryStringEncoded(@Query(value = "name", encoded = true) String name,
                                                   @Query("age") int age);

        @POST("/post")
        @Headers("content-type: application/json; charset=UTF-8")
        CompletableFuture<Response<Void>> post(@Body Pojo pojo);

        @POST("/postForm")
        @FormUrlEncoded
        CompletableFuture<Response<Pojo>> postForm(@Field("name") String name,
                                                   @Field("age") int age);

        @POST("/postForm")
        @FormUrlEncoded
        CompletableFuture<Response<Pojo>> postFormEncoded(@Field(value = "name", encoded = true) String name,
                                                          @Field("age") int age);

        @POST("/postCustomContentType")
        CompletableFuture<Response<Void>> postCustomContentType(
                @Header("Content-Type") @Nullable String contentType);

        @GET
        CompletableFuture<Pojo> fullUrl(@Url String url);

        @GET("pojo")
        CompletableFuture<Pojo> pojoNotRoot();

        @GET("/pathWithName/{name}")
        CompletableFuture<Pojo> customPath(@Path("name") String name, @Query("age") int age);

        @GET("{path}")
        CompletableFuture<Pojo> customPathEncoded(@Path(value = "path", encoded = true) String path);

        @GET("/headers")
        CompletableFuture<Pojo> customHeaders(
                @Header("x-header1") String header1, @Header("x-header2") String header2);
    }

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final Converter.Factory converterFactory = JacksonConverterFactory.create(objectMapper);

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/pojo", new AbstractHttpService() {
                  @Override
                  protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                      return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8,
                                             "{\"name\":\"Cony\", \"age\":26}");
                  }
              })
              .serviceUnder("/pathWithName", new AbstractHttpService() {

                  @Override
                  protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                      return HttpResponse.of(req.aggregate().handle((aReq, cause) -> {
                          final String name = ctx.mappedPath().substring(1);
                          final int age = QueryParams.fromQueryString(ctx.query()).getInt("age", -1);
                          return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8,
                                                 "{\"name\":\"" + name +
                                                 "\", \"age\":" + age + '}');
                      }));
                  }
              })
              .service("/nest/pojo", new AbstractHttpService() {
                  @Override
                  protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                      return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8,
                                             "{\"name\":\"Leonard\", \"age\":21}");
                  }
              })
              .service("/pojos", new AbstractHttpService() {
                  @Override
                  protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                      return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8,
                                             "[{\"name\":\"Cony\", \"age\":26}," +
                                             "{\"name\":\"Leonard\", \"age\":21}]");
                  }
              })
              .service("/queryString", new AbstractHttpService() {
                  @Override
                  protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                      return HttpResponse.of(req.aggregate().handle((aReq, cause) -> {
                          final QueryParams params = QueryParams.fromQueryString(ctx.query());
                          return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8,
                                                 "{\"name\":\"" + params.get("name", "<NULL>") + "\", " +
                                                 "\"age\":" + params.getInt("age", -1) + '}');
                      }));
                  }
              })
              .service("/post", new AbstractHttpService() {
                  @Override
                  protected HttpResponse doPost(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                      return HttpResponse.of(req.aggregate().handle((aReq, cause) -> {
                          if (cause != null) {
                              return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR,
                                                     MediaType.PLAIN_TEXT_UTF_8,
                                                     Exceptions.traceText(cause));
                          }
                          final String text = aReq.contentUtf8();
                          final Pojo request;
                          try {
                              request = objectMapper.readValue(text, Pojo.class);
                          } catch (IOException e) {
                              return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR,
                                                     MediaType.PLAIN_TEXT_UTF_8,
                                                     Exceptions.traceText(e));
                          }
                          assertThat(request).isEqualTo(new Pojo("Cony", 26));
                          return HttpResponse.of(HttpStatus.OK);
                      }));
                  }
              })
              .service("/postForm", new AbstractHttpService() {
                  @Override
                  protected HttpResponse doPost(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                      return HttpResponse.of(req.aggregate().handle((aReq, cause) -> {
                          if (cause != null) {
                              return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR,
                                                     MediaType.PLAIN_TEXT_UTF_8,
                                                     Exceptions.traceText(cause));
                          }
                          final QueryParams params =
                                  QueryParams.fromQueryString(aReq.contentUtf8());
                          return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8,
                                                 "{\"name\":\"" + params.get("name", "<NULL>") + "\", " +
                                                 "\"age\":" + params.getInt("age", -1) + '}');
                      }));
                  }
              })
              .service("/postCustomContentType", new AbstractHttpService() {
                  @Override
                  protected HttpResponse doPost(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                      return HttpResponse.of(req.aggregate().handle((aReq, cause) -> {
                          if (cause != null) {
                              return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR,
                                                     MediaType.PLAIN_TEXT_UTF_8,
                                                     Exceptions.traceText(cause));
                          }
                          assertThat(req.contentType()).isNull();
                          return HttpResponse.of(HttpStatus.OK);
                      }));
                  }
              })
              .service("/headers", new AbstractHttpService() {
                  @Override
                  protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                      assertThat(req.headers().get(HttpHeaderNames.of("x-header1"))).isEqualTo("foo");
                      assertThat(req.headers().get(HttpHeaderNames.of("x-header2"))).isEqualTo("bar");
                      return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8,
                                             "{\"name\":\"Cony\", \"age\":26}");
                  }
              });
            sb.requestTimeout(Duration.of(30, ChronoUnit.SECONDS));
        }
    };

    @ParameterizedTest
    @ArgumentsSource(ServiceProvider.class)
    void pojo(Service service) throws Exception {
        final Pojo pojo = service.pojo().get();
        assertThat(pojo).isEqualTo(new Pojo("Cony", 26));
    }

    @ParameterizedTest
    @ArgumentsSource(ServiceProvider.class)
    void pojoNotRoot(Service service) throws Exception {
        final Pojo pojo = service.pojoNotRoot().get();
        assertThat(pojo).isEqualTo(new Pojo("Cony", 26));
    }

    @ParameterizedTest
    @ArgumentsSource(ServiceProvider.class)
    void pojos(Service service) throws Exception {
        final List<Pojo> pojos = service.pojos().get();
        assertThat(pojos.get(0)).isEqualTo(new Pojo("Cony", 26));
        assertThat(pojos.get(1)).isEqualTo(new Pojo("Leonard", 21));
    }

    @ParameterizedTest
    @ArgumentsSource(ServiceProvider.class)
    void queryString(Service service) throws Exception {
        final Pojo response = service.queryString("Cony", 26).get();
        assertThat(response).isEqualTo(new Pojo("Cony", 26));
    }

    @ParameterizedTest
    @ArgumentsSource(ServiceProvider.class)
    void queryString_withSpecialCharacter(Service service) throws Exception {
        Pojo response = service.queryString("Foo+Bar", 33).get();
        assertThat(response).isEqualTo(new Pojo("Foo+Bar", 33));

        response = service.queryString("Foo&name=Bar", 34).get();
        assertThat(response).isEqualTo(new Pojo("Foo&name=Bar", 34));

        response = service.queryString("Foo;Bar", 33).get();
        assertThat(response).isEqualTo(new Pojo("Foo;Bar", 33));

        response = service.queryString("Foo%2BBar", 33).get();
        assertThat(response).isEqualTo(new Pojo("Foo%2BBar", 33));

        response = service.queryString("Foo%26name%3DBar", 33).get();
        assertThat(response).isEqualTo(new Pojo("Foo%26name%3DBar", 33));
    }

    @ParameterizedTest
    @ArgumentsSource(ServiceProvider.class)
    void queryStringEncoded(Service service) throws Exception {
        Pojo response = service.queryStringEncoded("Foo%2BBar", 33).get();
        assertThat(response).isEqualTo(new Pojo("Foo+Bar", 33));

        response = service.queryStringEncoded("Foo+Bar", 33).get();
        assertThat(response).isEqualTo(new Pojo("Foo Bar", 33));

        response = service.queryStringEncoded("Foo&name=Bar", 33).get();
        assertThat(response).isEqualTo(new Pojo("Foo&name=Bar", 33));

        response = service.queryStringEncoded("Foo%26name%3DBar", 33).get();
        assertThat(response).isEqualTo(new Pojo("Foo&name=Bar", 33));
    }

    @ParameterizedTest
    @ArgumentsSource(ServiceProvider.class)
    void post(Service service) throws Exception {
        final Response<Void> response = service.post(new Pojo("Cony", 26)).get();
        assertThat(response.isSuccessful()).isTrue();
    }

    @ParameterizedTest
    @ArgumentsSource(ServiceProvider.class)
    void form(Service service) throws Exception {
        assertThat(service.postForm("Cony", 26).get().body()).isEqualTo(new Pojo("Cony", 26));
        assertThat(service.postForm("Foo+Bar", 26).get().body()).isEqualTo(new Pojo("Foo+Bar", 26));
        assertThat(service.postForm("Foo%2BBar", 26).get().body()).isEqualTo(new Pojo("Foo%2BBar", 26));
    }

    @ParameterizedTest
    @ArgumentsSource(ServiceProvider.class)
    void formEncoded(Service service) throws Exception {
        assertThat(service.postFormEncoded("Cony", 26).get().body()).isEqualTo(new Pojo("Cony", 26));
        assertThat(service.postFormEncoded("Foo+Bar", 26).get().body()).isEqualTo(new Pojo("Foo Bar", 26));
        assertThat(service.postFormEncoded("Foo%2BBar", 26).get().body()).isEqualTo(new Pojo("Foo+Bar", 26));
    }

    @ParameterizedTest
    @ArgumentsSource(ServiceProvider.class)
    void pojo_returnCall(Service service) throws Exception {
        final Pojo pojo = service.pojoReturnCall().execute().body();
        assertThat(pojo).isEqualTo(new Pojo("Cony", 26));
    }

    @ParameterizedTest
    @ArgumentsSource(ServiceProvider.class)
    void pojo_returnCallCancelBeforeEnqueue(Service service) throws Exception {
        final CountDownLatch countDownLatch = new CountDownLatch(1);
        final Call<Pojo> pojoCall = service.pojoReturnCall();
        pojoCall.cancel();
        pojoCall.enqueue(new Callback<Pojo>() {
            @Override
            public void onResponse(Call<Pojo> call, Response<Pojo> response) {
            }

            @Override
            public void onFailure(Call<Pojo> call, Throwable t) {
                countDownLatch.countDown();
            }
        });
        assertThat(countDownLatch.await(3, TimeUnit.SECONDS)).isTrue();
    }

    @ParameterizedTest
    @ArgumentsSource(ServiceProvider.class)
    void pojo_returnCallCancelAfterComplete(Service service) throws Exception {
        final CountDownLatch countDownLatch = new CountDownLatch(1);
        final AtomicInteger failCount = new AtomicInteger(0);
        final Call<Pojo> pojoCall = service.pojoReturnCall();
        pojoCall.enqueue(new Callback<Pojo>() {
            @Override
            public void onResponse(Call<Pojo> call, Response<Pojo> response) {
                countDownLatch.countDown();
            }

            @Override
            public void onFailure(Call<Pojo> call, Throwable t) {
                failCount.incrementAndGet();
            }
        });
        assertThat(countDownLatch.await(3, TimeUnit.SECONDS)).isTrue();
        pojoCall.cancel();
        assertThat(failCount.intValue()).isZero();
    }

    @ParameterizedTest
    @ArgumentsSource(ServiceProvider.class)
    void respectsHttpClientUri(Service service) throws Exception {
        final Response<Pojo> response = service.postForm("Cony", 26).get();
        assertThat(response.raw().request().url()).isEqualTo(
                new HttpUrl.Builder().scheme("http")
                                     .host("127.0.0.1")
                                     .port(server.httpPort())
                                     .addPathSegment("postForm")
                                     .build());
    }

    @Test
    void respectsHttpClientUri_endpointGroup() throws Exception {
        final EndpointGroup group = EndpointGroup.of(Endpoint.of("127.0.0.1", server.httpPort()),
                                                     Endpoint.of("127.0.0.1", server.httpPort()));

        final Service service = ArmeriaRetrofit.builder("http", group)
                                               .addConverterFactory(converterFactory)
                                               .build()
                                               .create(Service.class);

        try (ClientRequestContextCaptor ctxCaptor = Clients.newContextCaptor()) {
            final Response<Pojo> response = service.postForm("Cony", 26).get();

            final RequestLog log = ctxCaptor.get().log().whenComplete().join();
            assertThat(log.sessionProtocol()).isSameAs(SessionProtocol.H2C);
            assertThat(log.requestHeaders().authority()).isEqualTo("127.0.0.1:" + server.httpPort());

            final HttpUrl url = response.raw().request().url();
            assertThat(url.scheme()).isEqualTo("http");
            assertThat(url.host()).startsWith("armeria-group-");
            assertThat(url.pathSegments()).containsExactly("postForm");
        }
    }

    @Test
    void urlAnnotation() throws Exception {
        final EndpointGroup groupFoo = EndpointGroup.of(Endpoint.of("127.0.0.1", 1),
                                                        Endpoint.of("127.0.0.1", 1));
        final EndpointGroup groupBar = EndpointGroup.of(Endpoint.of("127.0.0.1", server.httpPort()),
                                                        Endpoint.of("127.0.0.1", server.httpPort()));

        final WebClient baseWebClient = WebClient.builder(SessionProtocol.HTTP, groupFoo)
                                                 .endpointRemapper(endpoint -> {
                                                     if ("group_bar".equals(endpoint.host())) {
                                                         return groupBar;
                                                     } else {
                                                         return endpoint;
                                                     }
                                                 })
                                                 .build();

        final Service service = ArmeriaRetrofit.builder(baseWebClient)
                                               .addConverterFactory(converterFactory)
                                               .build()
                                               .create(Service.class);

        // The request should never go to 'groupFoo'.
        final Pojo pojo = service.fullUrl("http://group_bar/pojo").get();
        assertThat(pojo).isEqualTo(new Pojo("Cony", 26));
    }

    @Test
    void urlAnnotation_uriWithoutScheme() throws Exception {
        final EndpointGroup group = EndpointGroup.of(Endpoint.of("127.0.0.1", server.httpPort()),
                                                     Endpoint.of("127.0.0.1", server.httpPort()));

        final WebClient baseWebClient = WebClient.builder("http://127.0.0.1:1")
                                                 .endpointRemapper(endpoint -> {
                                                     if ("my-group".equals(endpoint.host())) {
                                                         return group;
                                                     } else {
                                                         return endpoint;
                                                     }
                                                 })
                                                 .build();

        final Service service = ArmeriaRetrofit.builder(baseWebClient)
                                               .addConverterFactory(converterFactory)
                                               .build()
                                               .create(Service.class);

        assertThat(service.fullUrl("//localhost:" + server.httpPort() + "/nest/pojo").get()).isEqualTo(
                new Pojo("Leonard", 21));
        assertThat(service.fullUrl("//my-group/nest/pojo").get()).isEqualTo(new Pojo("Leonard", 21));

        assertThat(service.fullUrl("//localhost:" + server.httpPort() + "/pojo").get()).isEqualTo(
                new Pojo("Cony", 26));
        assertThat(service.fullUrl("//my-group/pojo").get()).isEqualTo(new Pojo("Cony", 26));
    }

    @Test
    void sessionProtocolH1C() throws Exception {
        final Service service = ArmeriaRetrofit.builder("h1c://127.0.0.1:" + server.httpPort())
                                               .addConverterFactory(converterFactory)
                                               .build()
                                               .create(Service.class);

        try (ClientRequestContextCaptor ctxCaptor = Clients.newContextCaptor()) {
            final Pojo pojo = service.pojo().get();
            assertThat(pojo).isEqualTo(new Pojo("Cony", 26));

            final RequestLog log = ctxCaptor.get().log().whenComplete().join();
            assertThat(log.sessionProtocol()).isSameAs(SessionProtocol.H1C);
        }
    }

    @Test
    void baseUrlContainsPath() throws Exception {
        final Service service = ArmeriaRetrofit.builder(server.httpUri() + "/nest/")
                                               .addConverterFactory(converterFactory)
                                               .build()
                                               .create(Service.class);
        assertThat(service.pojoNotRoot().get()).isEqualTo(new Pojo("Leonard", 21));
        assertThat(service.pojo().get()).isEqualTo(new Pojo("Cony", 26));
    }

    @ParameterizedTest
    @ArgumentsSource(ServiceProvider.class)
    void customPath(Service service) throws Exception {
        assertThat(service.customPath("Foo", 23).get()).isEqualTo(new Pojo("Foo", 23));
        assertThat(service.customPath("Foo+Bar", 24).get()).isEqualTo(new Pojo("Foo+Bar", 24));
        // Slash in a path variable will be percent-encoded.
        assertThat(service.customPath("Foo+Bar/Hoge", 24).get()).isEqualTo(new Pojo("Foo+Bar%2FHoge", 24));
        assertThat(service.customPath("Foo+Bar%2fHoge", 24).get()).isEqualTo(new Pojo("Foo+Bar%252fHoge", 24));
        assertThat(service.customPath("Foo%2bBar", 24).get()).isEqualTo(new Pojo("Foo%252bBar", 24));
    }

    @ParameterizedTest
    @ArgumentsSource(ServiceProvider.class)
    void customPathEncoded(Service service) throws Exception {
        assertThat(service.customPathEncoded("/nest/pojo").get()).isEqualTo(new Pojo("Leonard", 21));
        assertThat(service.customPathEncoded("nest/pojo").get()).isEqualTo(new Pojo("Leonard", 21));
        assertThat(service.customPathEncoded("/pojo").get()).isEqualTo(new Pojo("Cony", 26));
        assertThat(service.customPathEncoded("pojo").get()).isEqualTo(new Pojo("Cony", 26));
    }

    @ParameterizedTest
    @ArgumentsSource(ServiceProvider.class)
    void customHeaders(Service service) throws Exception {
        assertThat(service.customHeaders("foo", "bar").get()).isEqualTo(new Pojo("Cony", 26));
    }

    @Test
    void customNewClientFunction() throws Exception {
        final AtomicInteger defaultCounter = new AtomicInteger();
        final AtomicInteger customCounter = new AtomicInteger();
        final WebClient defaultWebClient =
                WebClient.builder("h1c://127.0.0.1:" + server.httpPort())
                         .decorator((delegate, ctx, req) -> {
                             defaultCounter.incrementAndGet();
                             return delegate.execute(ctx, req);
                         })
                         .build();

        final Service service = ArmeriaRetrofit
                .builder(defaultWebClient)
                .addConverterFactory(converterFactory)
                .nonBaseClientFactory((protocol, endpoint) -> {
                    if ("not-default".equals(endpoint.host())) {
                        return WebClient
                                .builder("h2c://127.0.0.1:" + server.httpPort())
                                .decorator((delegate, ctx, req) -> {
                                    customCounter.incrementAndGet();
                                    return delegate.execute(ctx, req);
                                })
                                .build();
                    }

                    return fail("Unexpected URL: %s", endpoint);
                })
                .build().create(Service.class);

        service.pojo().get();
        assertThat(defaultCounter.get()).isOne();

        service.fullUrl("http://not-default/pojo").get();
        assertThat(defaultCounter.get()).isOne();
        assertThat(customCounter.get()).isOne();
    }

    /**
     * Tests https://github.com/line/armeria/pull/386
     */
    @ParameterizedTest
    @ArgumentsSource(ServiceProvider.class)
    void nullContentType(Service service) throws Exception {
        final Response<Void> response = service.postCustomContentType(null).get();
        assertThat(response.code()).isEqualTo(200);
    }

    private static class ServiceProvider implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
            return Stream.of(true, false)
                         .map(streaming -> ArmeriaRetrofit.builder(server.httpUri())
                                                          .streaming(streaming)
                                                          .addConverterFactory(converterFactory)
                                                          .build()
                                                          .create(Service.class))
                         .map(Arguments::of);
        }
    }

    public static Stream<Arguments> preprocessor_args() {
        final HttpPreprocessor preprocessor = HttpPreprocessor.of(SessionProtocol.HTTP, server.httpEndpoint());
        return Stream.of(
                Arguments.of(
                        ArmeriaRetrofit.builder(preprocessor)
                                       .addConverterFactory(converterFactory)
                                       .build()
                                       .create(Service.class),
                        ArmeriaRetrofit.builder(WebClient.of(preprocessor))
                                       .addConverterFactory(converterFactory)
                                       .build()
                                       .create(Service.class)
                )
        );
    }

    @ParameterizedTest
    @MethodSource("preprocessor_args")
    void preprocessor(Service service) throws Exception {
        final Pojo pojo = service.pojo().get();
        assertThat(pojo).isEqualTo(new Pojo("Cony", 26));
    }
}
