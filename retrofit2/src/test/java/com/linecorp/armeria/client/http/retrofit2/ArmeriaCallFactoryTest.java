/*
 * Copyright (c) 2016 LINE Corporation. All rights reserved.
 * LINE Corporation PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.linecorp.armeria.client.http.retrofit2;

import static com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy.ROUND_ROBIN;
import static com.linecorp.armeria.common.util.Functions.voidFunction;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroupRegistry;
import com.linecorp.armeria.client.endpoint.StaticEndpointGroup;
import com.linecorp.armeria.client.http.HttpClient;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.http.HttpResponseWriter;
import com.linecorp.armeria.common.http.HttpSessionProtocols;
import com.linecorp.armeria.common.http.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.http.AbstractHttpService;
import com.linecorp.armeria.test.AbstractServerTest;

import io.netty.handler.codec.http.QueryStringDecoder;
import okhttp3.HttpUrl;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.adapter.java8.Java8CallAdapterFactory;
import retrofit2.converter.jackson.JacksonConverterFactory;
import retrofit2.http.Body;
import retrofit2.http.Field;
import retrofit2.http.FormUrlEncoded;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.Headers;
import retrofit2.http.POST;
import retrofit2.http.Query;

public class ArmeriaCallFactoryTest extends AbstractServerTest {
    public static class Pojo {
        @JsonProperty("name")
        String name;
        @JsonProperty("age")
        int age;

        @JsonCreator
        public Pojo(@JsonProperty("name") String name, @JsonProperty("age") int age) {
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
            Pojo other = (Pojo) o;
            return name.equals(other.name) && age == other.age;
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

        @POST("/post")
        @Headers("content-type: application/json; charset=UTF-8")
        CompletableFuture<Response<Void>> post(@Body Pojo pojo);

        @POST("/postForm")
        @FormUrlEncoded
        CompletableFuture<Response<Void>> postForm(@Field("name") String name,
                                                   @Field("age") int age);

        @POST("/postCustomContentType")
        CompletableFuture<Response<Void>> postCustomContentType(@Header("Content-Type") String contentType);
    }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    protected void configureServer(ServerBuilder sb) throws Exception {
        sb.port(0, HttpSessionProtocols.HTTP)
          .serviceAt("/pojo", new AbstractHttpService() {
              @Override
              protected void doGet(ServiceRequestContext ctx,
                                   HttpRequest req, HttpResponseWriter res) throws Exception {
                  res.respond(HttpStatus.OK, MediaType.JSON_UTF_8,
                              "{\"name\":\"Cony\", \"age\":26}");
              }
          })
          .serviceAt("/pojos", new AbstractHttpService() {
              @Override
              protected void doGet(ServiceRequestContext ctx,
                                   HttpRequest req, HttpResponseWriter res) throws Exception {
                  res.respond(HttpStatus.OK, MediaType.JSON_UTF_8,
                              "[{\"name\":\"Cony\", \"age\":26}," +
                              "{\"name\":\"Leonard\", \"age\":21}]");
              }
          })
          .serviceAt("/queryString", new AbstractHttpService() {
              @Override
              protected void doGet(ServiceRequestContext ctx,
                                   HttpRequest req, HttpResponseWriter res) throws Exception {
                  req.aggregate().handle(voidFunction((aReq, cause) -> {
                      Map<String, List<String>> params = new QueryStringDecoder(aReq.path())
                              .parameters();
                      res.respond(HttpStatus.OK, MediaType.JSON_UTF_8,
                                  "{\"name\":\"" + params.get("name").get(0) + "\", " +
                                  "\"age\":" + params.get("age").get(0) + '}');
                  }));
              }
          })
          .serviceAt("/post", new AbstractHttpService() {
              @Override
              protected void doPost(ServiceRequestContext ctx,
                                    HttpRequest req, HttpResponseWriter res) throws Exception {
                  req.aggregate().handle(voidFunction((aReq, cause) -> {
                      if (cause != null) {
                          res.respond(HttpStatus.INTERNAL_SERVER_ERROR,
                                      MediaType.PLAIN_TEXT_UTF_8,
                                      Throwables.getStackTraceAsString(cause));
                          return;
                      }
                      String text = aReq.content().toStringUtf8();
                      final Pojo request;
                      try {
                          request = OBJECT_MAPPER.readValue(text, Pojo.class);
                      } catch (IOException e) {
                          res.respond(HttpStatus.INTERNAL_SERVER_ERROR,
                                      MediaType.PLAIN_TEXT_UTF_8,
                                      Throwables.getStackTraceAsString(e));
                          return;
                      }
                      assertThat(request).isEqualTo(new Pojo("Cony", 26));
                      res.respond(HttpStatus.OK);
                  }));
              }
          })
          .serviceAt("/postForm", new AbstractHttpService() {
              @Override
              protected void doPost(ServiceRequestContext ctx,
                                    HttpRequest req, HttpResponseWriter res) throws Exception {
                  req.aggregate().handle(voidFunction((aReq, cause) -> {
                      if (cause != null) {
                          res.respond(HttpStatus.INTERNAL_SERVER_ERROR,
                                      MediaType.PLAIN_TEXT_UTF_8,
                                      Throwables.getStackTraceAsString(cause));
                          return;
                      }
                      Map<String, List<String>> params = new QueryStringDecoder(
                              aReq.content().toStringUtf8(), false)
                              .parameters();
                      assertThat(params).isEqualTo(ImmutableMap.of("name", ImmutableList.of("Cony"),
                                                                   "age", ImmutableList.of("26")));
                      res.respond(HttpStatus.OK);
                  }));
              }
          })
          .serviceAt("/postCustomContentType", new AbstractHttpService() {
            @Override
            protected void doPost(ServiceRequestContext ctx,
                                  HttpRequest req, HttpResponseWriter res) throws Exception {
                req.aggregate().handle(voidFunction((aReq, cause) -> {
                    if (cause != null) {
                        res.respond(HttpStatus.INTERNAL_SERVER_ERROR,
                                    MediaType.PLAIN_TEXT_UTF_8,
                                    Throwables.getStackTraceAsString(cause));
                        return;
                    }
                    Map<String, List<String>> params = new QueryStringDecoder(
                            aReq.content().toStringUtf8(), false)
                            .parameters();
                    assertThat(params).isEmpty();
                    res.respond(HttpStatus.OK);
                }));
            }
        });
    }

    private Service service;

    @Before
    public void setUp() {
        service = ArmeriaRetrofit.builder(Clients.newClient(ClientFactory.DEFAULT,
                                                            "none+http://127.0.0.1:" + httpPort(),
                                                            HttpClient.class))
                                 .addConverterFactory(JacksonConverterFactory.create(OBJECT_MAPPER))
                                 .addCallAdapterFactory(Java8CallAdapterFactory.create())
                                 .build()
                                 .create(Service.class);
    }

    @Test
    public void pojo() throws Exception {
        Pojo pojo = service.pojo().get();
        assertThat(pojo).isEqualTo(new Pojo("Cony", 26));
    }

    @Test
    public void pojos() throws Exception {
        List<Pojo> pojos = service.pojos().get();
        assertThat(pojos.get(0)).isEqualTo(new Pojo("Cony", 26));
        assertThat(pojos.get(1)).isEqualTo(new Pojo("Leonard", 21));
    }

    @Test
    public void queryString() throws Exception {
        Pojo response = service.queryString("Cony", 26).get();
        assertThat(response).isEqualTo(new Pojo("Cony", 26));
    }

    @Test
    public void post() throws Exception {
        Response<Void> response = service.post(new Pojo("Cony", 26)).get();
        assertThat(response.isSuccessful()).isTrue();
    }

    @Test
    public void formEncoded() throws Exception {
        Response<Void> response = service.postForm("Cony", 26).get();
        assertThat(response.isSuccessful()).isTrue();
    }

    @Test
    public void pojo_returnCall() throws Exception {
        Pojo pojo = service.pojoReturnCall().execute().body();
        assertThat(pojo).isEqualTo(new Pojo("Cony", 26));
    }

    @Test
    public void pojo_returnCallCancelBeforeEnqueue() throws Exception {
        CountDownLatch countDownLatch = new CountDownLatch(1);
        Call<Pojo> pojoCall = service.pojoReturnCall();
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
        CountDownLatch countDownLatch = new CountDownLatch(1);
        AtomicInteger failCount = new AtomicInteger(0);
        Call<Pojo> pojoCall = service.pojoReturnCall();
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
        Response<Void> response = service.postForm("Cony", 26).get();
        assertThat(response.raw().request().url()).isEqualTo(
                new HttpUrl.Builder().scheme("http")
                                     .host("127.0.0.1")
                                     .port(httpPort())
                                     .addPathSegment("postForm")
                                     .build());
    }

    @Test
    public void respectsHttpClientUri_endpointGroup() throws Exception {
        EndpointGroupRegistry.register("foo", new StaticEndpointGroup(Endpoint.of("127.0.0.1", httpPort())),
                                       ROUND_ROBIN);
        Service service = ArmeriaRetrofit.builder(Clients.newClient(ClientFactory.DEFAULT,
                                                                    "none+http://group:foo/",
                                                                    HttpClient.class))
                                         .addConverterFactory(JacksonConverterFactory.create(OBJECT_MAPPER))
                                         .addCallAdapterFactory(Java8CallAdapterFactory.create())
                                         .build()
                                         .create(Service.class);
        Response<Void> response = service.postForm("Cony", 26).get();
        // TODO(ide) Use the actual `host:port`. See https://github.com/line/armeria/issues/379
        assertThat(response.raw().request().url()).isEqualTo(
                new HttpUrl.Builder().scheme("http")
                                     .host("group_foo")
                                     .addPathSegment("postForm")
                                     .build());
    }

    /**
     * Tests https://github.com/line/armeria/pull/386
     */
    @Test
    public void nullContentType() throws Exception {
        Response<Void> response = service.postCustomContentType(null).get();
        assertThat(response.code()).isEqualTo(200);
    }
}
