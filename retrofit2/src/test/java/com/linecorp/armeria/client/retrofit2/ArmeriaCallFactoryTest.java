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

import static com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy.ROUND_ROBIN;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;

import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroupRegistry;
import com.linecorp.armeria.client.endpoint.StaticEndpointGroup;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.PathAndQuery;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.server.ServerRule;

import io.netty.handler.codec.http.QueryStringDecoder;
import okhttp3.HttpUrl;
import retrofit2.Call;
import retrofit2.Callback;
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

@RunWith(Parameterized.class)
public class ArmeriaCallFactoryTest {
    public static class Pojo {
        @Nullable
        @JsonProperty("name")
        String name;
        @JsonProperty("age")
        int age;

        @JsonCreator
        public Pojo(@JsonProperty("name") @Nullable String name, @JsonProperty("age") int age) {
            this.name = name;
            this.age = age;
        }

        @Override
        public boolean equals(Object o) {
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

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @ClassRule
    public static final ServerRule server = new ServerRule() {
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
                      return HttpResponse.from(req.aggregate().handle((aReq, cause) -> {
                          final Map<String, List<String>> params = new QueryStringDecoder(aReq.path())
                                  .parameters();
                          final String fullPath = PathAndQuery.parse(req.path()).path();
                          return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8,
                                                 "{\"name\":\"" + fullPath.replace("/pathWithName/", "") +
                                                 "\", \"age\":" + params.get("age").get(0) + '}');
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
                      return HttpResponse.from(req.aggregate().handle((aReq, cause) -> {
                          final Map<String, List<String>> params = new QueryStringDecoder(aReq.path())
                                  .parameters();
                          return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8,
                                                 "{\"name\":\"" + params.get("name").get(0) + "\", " +
                                                 "\"age\":" + params.get("age").get(0) + '}');
                      }));
                  }
              })
              .service("/post", new AbstractHttpService() {
                  @Override
                  protected HttpResponse doPost(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                      return HttpResponse.from(req.aggregate().handle((aReq, cause) -> {
                          if (cause != null) {
                              return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR,
                                                     MediaType.PLAIN_TEXT_UTF_8,
                                                     Exceptions.traceText(cause));
                          }
                          final String text = aReq.contentUtf8();
                          final Pojo request;
                          try {
                              request = OBJECT_MAPPER.readValue(text, Pojo.class);
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
                      return HttpResponse.from(req.aggregate().handle((aReq, cause) -> {
                          if (cause != null) {
                              return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR,
                                                     MediaType.PLAIN_TEXT_UTF_8,
                                                     Exceptions.traceText(cause));
                          }
                          final Map<String, List<String>> params = new QueryStringDecoder(
                                  aReq.contentUtf8(), false).parameters();
                          return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8,
                                                 "{\"name\":\"" + params.get("name").get(0) + "\", " +
                                                 "\"age\":" + params.get("age").get(0) + '}');
                      }));
                  }
              })
              .service("/postCustomContentType", new AbstractHttpService() {
                  @Override
                  protected HttpResponse doPost(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                      return HttpResponse.from(req.aggregate().handle((aReq, cause) -> {
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
            sb.defaultRequestTimeout(Duration.of(30, ChronoUnit.SECONDS));
        }
    };

    @Parameters(name = "{index}: streaming={0}")
    public static Collection<Boolean> parameters() {
        return ImmutableList.of(true, false);
    }

    private final boolean streaming;

    private Service service;

    public ArmeriaCallFactoryTest(boolean streaming) {
        this.streaming = streaming;
    }

    @Before
    public void setUp() {
        service = new ArmeriaRetrofitBuilder()
                .baseUrl(server.uri("/"))
                .streaming(streaming)
                .addConverterFactory(JacksonConverterFactory.create(OBJECT_MAPPER))
                .build()
                .create(Service.class);
    }

    @Test
    public void pojo() throws Exception {
        final Pojo pojo = service.pojo().get();
        assertThat(pojo).isEqualTo(new Pojo("Cony", 26));
    }

    @Test
    public void pojoNotRoot() throws Exception {
        final Pojo pojo = service.pojoNotRoot().get();
        assertThat(pojo).isEqualTo(new Pojo("Cony", 26));
    }

    @Test
    public void pojos() throws Exception {
        final List<Pojo> pojos = service.pojos().get();
        assertThat(pojos.get(0)).isEqualTo(new Pojo("Cony", 26));
        assertThat(pojos.get(1)).isEqualTo(new Pojo("Leonard", 21));
    }

    @Test
    public void queryString() throws Exception {
        final Pojo response = service.queryString("Cony", 26).get();
        assertThat(response).isEqualTo(new Pojo("Cony", 26));
    }

    @Test
    public void queryString_withSpecialCharacter() throws Exception {
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

    @Test
    public void queryStringEncoded() throws Exception {
        Pojo response = service.queryStringEncoded("Foo%2BBar", 33).get();
        assertThat(response).isEqualTo(new Pojo("Foo+Bar", 33));

        response = service.queryStringEncoded("Foo+Bar", 33).get();
        assertThat(response).isEqualTo(new Pojo("Foo Bar", 33));

        response = service.queryStringEncoded("Foo&name=Bar", 33).get();
        assertThat(response).isEqualTo(new Pojo("Foo&name=Bar", 33));

        response = service.queryStringEncoded("Foo%26name%3DBar", 33).get();
        assertThat(response).isEqualTo(new Pojo("Foo&name=Bar", 33));
    }

    @Test
    public void post() throws Exception {
        final Response<Void> response = service.post(new Pojo("Cony", 26)).get();
        assertThat(response.isSuccessful()).isTrue();
    }

    @Test
    public void form() throws Exception {
        assertThat(service.postForm("Cony", 26).get().body()).isEqualTo(new Pojo("Cony", 26));
        assertThat(service.postForm("Foo+Bar", 26).get().body()).isEqualTo(new Pojo("Foo+Bar", 26));
        assertThat(service.postForm("Foo%2BBar", 26).get().body()).isEqualTo(new Pojo("Foo%2BBar", 26));
    }

    @Test
    public void formEncoded() throws Exception {
        assertThat(service.postFormEncoded("Cony", 26).get().body()).isEqualTo(new Pojo("Cony", 26));
        assertThat(service.postFormEncoded("Foo+Bar", 26).get().body()).isEqualTo(new Pojo("Foo Bar", 26));
        assertThat(service.postFormEncoded("Foo%2BBar", 26).get().body()).isEqualTo(new Pojo("Foo+Bar", 26));
    }

    @Test
    public void pojo_returnCall() throws Exception {
        final Pojo pojo = service.pojoReturnCall().execute().body();
        assertThat(pojo).isEqualTo(new Pojo("Cony", 26));
    }

    @Test
    public void pojo_returnCallCancelBeforeEnqueue() throws Exception {
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

    @Test
    public void pojo_returnCallCancelAfterComplete() throws Exception {
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

    @Test
    public void respectsHttpClientUri() throws Exception {
        final Response<Pojo> response = service.postForm("Cony", 26).get();
        assertThat(response.raw().request().url()).isEqualTo(
                new HttpUrl.Builder().scheme("http")
                                     .host("127.0.0.1")
                                     .port(server.httpPort())
                                     .addPathSegment("postForm")
                                     .build());
    }

    @Test
    public void respectsHttpClientUri_endpointGroup() throws Exception {
        EndpointGroupRegistry.register("foo",
                                       new StaticEndpointGroup(Endpoint.of("127.0.0.1", server.httpPort())),
                                       ROUND_ROBIN);
        final Service service = new ArmeriaRetrofitBuilder()
                .baseUrl("http://group:foo/")
                .addConverterFactory(JacksonConverterFactory.create(OBJECT_MAPPER))
                .build()
                .create(Service.class);
        final Response<Pojo> response = service.postForm("Cony", 26).get();
        // TODO(ide) Use the actual `host:port`. See https://github.com/line/armeria/issues/379
        assertThat(response.raw().request().url()).isEqualTo(
                new HttpUrl.Builder().scheme("http")
                                     .host("group_foo")
                                     .addPathSegment("postForm")
                                     .build());
    }

    @Test
    public void urlAnnotation() throws Exception {
        EndpointGroupRegistry.register("bar",
                                       new StaticEndpointGroup(Endpoint.of("127.0.0.1", server.httpPort())),
                                       ROUND_ROBIN);
        final Service service = new ArmeriaRetrofitBuilder()
                .baseUrl("http://group:foo/")
                .addConverterFactory(JacksonConverterFactory.create(OBJECT_MAPPER))
                .build()
                .create(Service.class);
        final Pojo pojo = service.fullUrl("http://group_bar/pojo").get();
        assertThat(pojo).isEqualTo(new Pojo("Cony", 26));
    }

    @Test
    public void urlAnnotation_uriWithoutScheme() throws Exception {
        EndpointGroupRegistry.register("bar",
                                       new StaticEndpointGroup(Endpoint.of("127.0.0.1", server.httpPort())),
                                       ROUND_ROBIN);
        assertThat(service.fullUrl("//localhost:" + server.httpPort() + "/nest/pojo").get()).isEqualTo(
                new Pojo("Leonard", 21));
        assertThat(service.fullUrl("//group_bar/nest/pojo").get()).isEqualTo(new Pojo("Leonard", 21));

        assertThat(service.fullUrl("//localhost:" + server.httpPort() + "/pojo").get()).isEqualTo(
                new Pojo("Cony", 26));
        assertThat(service.fullUrl("//group_bar/pojo").get()).isEqualTo(new Pojo("Cony", 26));
    }

    @Test
    public void sessionProtocolH1C() throws Exception {
        final Service service = new ArmeriaRetrofitBuilder()
                .baseUrl("h1c://127.0.0.1:" + server.httpPort())
                .addConverterFactory(JacksonConverterFactory.create(OBJECT_MAPPER))
                .build()
                .create(Service.class);
        final Pojo pojo = service.pojo().get();
        assertThat(pojo).isEqualTo(new Pojo("Cony", 26));
    }

    @Test
    public void baseUrlContainsPath() throws Exception {
        final Service service = new ArmeriaRetrofitBuilder()
                .baseUrl(server.uri("/nest/"))
                .addConverterFactory(JacksonConverterFactory.create(OBJECT_MAPPER))
                .build()
                .create(Service.class);
        assertThat(service.pojoNotRoot().get()).isEqualTo(new Pojo("Leonard", 21));
        assertThat(service.pojo().get()).isEqualTo(new Pojo("Cony", 26));
    }

    @Test
    public void customPath() throws Exception {
        assertThat(service.customPath("Foo", 23).get()).isEqualTo(new Pojo("Foo", 23));
        assertThat(service.customPath("Foo+Bar", 24).get()).isEqualTo(new Pojo("Foo+Bar", 24));
        assertThat(service.customPath("Foo+Bar/Hoge", 24).get()).isEqualTo(new Pojo("Foo+Bar/Hoge", 24));
        assertThat(service.customPath("Foo+Bar%2fHoge", 24).get()).isEqualTo(new Pojo("Foo+Bar%252fHoge", 24));
        assertThat(service.customPath("Foo%2bBar", 24).get()).isEqualTo(new Pojo("Foo%252bBar", 24));
    }

    @Test
    public void customPathEncoded() throws Exception {
        assertThat(service.customPathEncoded("/nest/pojo").get()).isEqualTo(new Pojo("Leonard", 21));
        assertThat(service.customPathEncoded("nest/pojo").get()).isEqualTo(new Pojo("Leonard", 21));
        assertThat(service.customPathEncoded("/pojo").get()).isEqualTo(new Pojo("Cony", 26));
        assertThat(service.customPathEncoded("pojo").get()).isEqualTo(new Pojo("Cony", 26));
    }

    @Test
    public void customHeaders() throws Exception {
        assertThat(service.customHeaders("foo", "bar").get()).isEqualTo(new Pojo("Cony", 26));
    }

    @Test
    public void customNewClientFunction() throws Exception {
        final AtomicInteger counter = new AtomicInteger();
        final Service service = new ArmeriaRetrofitBuilder()
                .baseUrl("h1c://127.0.0.1:" + server.httpPort())
                .addConverterFactory(JacksonConverterFactory.create(OBJECT_MAPPER))
                .withClientOptions((url, optionsBuilder) -> {
                    optionsBuilder.decorator((delegate, ctx, req) -> {
                        counter.incrementAndGet();
                        return delegate.execute(ctx, req);
                    });
                    return optionsBuilder;
                })
                .build().create(Service.class);

        service.pojo().get();
        assertThat(counter.get()).isEqualTo(1);

        service.fullUrl("http://localhost:" + server.httpPort() + "/pojo").get();
        assertThat(counter.get()).isEqualTo(2);
    }

    /**
     * Tests https://github.com/line/armeria/pull/386
     */
    @Test
    public void nullContentType() throws Exception {
        final Response<Void> response = service.postCustomContentType(null).get();
        assertThat(response.code()).isEqualTo(200);
    }
}
