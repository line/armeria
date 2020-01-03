/*
 * Copyright 2019 LINE Corporation
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
import static org.awaitility.Awaitility.await;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.metric.MetricCollectingClient;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;
import com.linecorp.armeria.common.metric.MoreMeters;
import com.linecorp.armeria.common.metric.NoopMeterRegistry;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit.server.ServerExtension;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import retrofit2.http.DELETE;
import retrofit2.http.GET;
import retrofit2.http.HEAD;
import retrofit2.http.HTTP;
import retrofit2.http.OPTIONS;
import retrofit2.http.PATCH;
import retrofit2.http.POST;
import retrofit2.http.PUT;

public class RetrofitClassAwareMeterIdPrefixFunctionTest {

    private static final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private static final ClientFactory clientFactory = ClientFactory.builder()
                                                                    .meterRegistry(meterRegistry)
                                                                    .build();

    interface Example {
        @DELETE("/foo")
        CompletableFuture<Void> deleteFoo();

        @GET("/foo")
        CompletableFuture<Void> getFoo();

        @HEAD("/foo")
        CompletableFuture<Void> headFoo();

        @HTTP(method = "TRACE", path = "/foo")
        CompletableFuture<Void> traceFoo();

        @OPTIONS("/foo")
        CompletableFuture<Void> optionsFoo();

        @PATCH("/foo")
        CompletableFuture<Void> patchFoo();

        @POST("/foo")
        CompletableFuture<Void> postFoo();

        @PUT("/foo")
        CompletableFuture<Void> putFoo();
    }

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/foo", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
                    return HttpResponse.of(HttpStatus.OK);
                }

                @Override
                protected HttpResponse doPost(ServiceRequestContext ctx, HttpRequest req) {
                    return HttpResponse.of(HttpStatus.OK);
                }

                @Override
                protected HttpResponse doTrace(ServiceRequestContext ctx, HttpRequest req) {
                    return HttpResponse.of(HttpStatus.OK);
                }
            });
        }
    };

    @Test
    void metrics() {
        final Example example =
                new ArmeriaRetrofitBuilder(clientFactory)
                        .baseUrl("h1c://127.0.0.1:" + server.httpPort())
                        .clientOptions(ClientOptions.builder()
                                                    .decorator(MetricCollectingClient.newDecorator(
                                                            RetrofitClassAwareMeterIdPrefixFunction
                                                                    .of("foo", Example.class)))
                                                    .build())
                        .build()
                        .create(Example.class);

        example.getFoo().join();
        await().untilAsserted(() -> assertThat(MoreMeters.measureAll(meterRegistry))
                .containsKeys(
                        "foo.activeRequests#value{httpMethod=GET,method=getFoo,path=/foo,service=Example}",
                        "foo.requestDuration#count{" +
                        "httpMethod=GET,httpStatus=200,method=getFoo,path=/foo,service=Example}"));

        example.postFoo().join();
        await().untilAsserted(() -> assertThat(MoreMeters.measureAll(meterRegistry))
                .containsKeys(
                        "foo.activeRequests#value{httpMethod=GET,method=getFoo,path=/foo,service=Example}",
                        "foo.requestDuration#count{" +
                        "httpMethod=GET,httpStatus=200,method=getFoo,path=/foo,service=Example}"));

        example.traceFoo().join();
        await().untilAsserted(() -> assertThat(MoreMeters.measureAll(meterRegistry))
                .containsKeys(
                        "foo.activeRequests#value{httpMethod=TRACE,method=traceFoo,path=/foo,service=Example}",
                        "foo.requestDuration#count{" +
                        "httpMethod=TRACE,httpStatus=200,method=traceFoo,path=/foo,service=Example}"));
    }

    @Test
    void metrics_withServiceTag() {
        final Example example =
                new ArmeriaRetrofitBuilder(clientFactory)
                        .baseUrl("h1c://127.0.0.1:" + server.httpPort())
                        .withClientOptions((s, clientOptionsBuilder) -> {
                            return clientOptionsBuilder.decorator(
                                    MetricCollectingClient.newDecorator(
                                            RetrofitClassAwareMeterIdPrefixFunction
                                                    .builder("foo", Example.class)
                                                    .withServiceTag("tservice", "fallbackService")
                                                    .build()));
                        })
                .build()
                .create(RetrofitClassAwareMeterIdPrefixFunctionTest.Example.class);

        example.getFoo().join();
        await().untilAsserted(() -> assertThat(MoreMeters.measureAll(meterRegistry))
                .containsKeys(
                        "foo.activeRequests#value{httpMethod=GET,method=getFoo,path=/foo,tservice=Example}",
                        "foo.requestDuration#count{" +
                        "httpMethod=GET,httpStatus=200,method=getFoo,path=/foo,tservice=Example}"));

        example.postFoo().join();
        await().untilAsserted(() -> assertThat(MoreMeters.measureAll(meterRegistry))
                .containsKeys(
                        "foo.activeRequests#value{httpMethod=GET,method=getFoo,path=/foo,tservice=Example}",
                        "foo.requestDuration#count{" +
                        "httpMethod=POST,httpStatus=200,method=postFoo,path=/foo,tservice=Example}"));

        example.traceFoo().join();
        await().untilAsserted(() -> assertThat(MoreMeters.measureAll(meterRegistry))
                .containsKeys(
                        "foo.activeRequests#value{httpMethod=TRACE,method=traceFoo,path=/foo,tservice=Example}",
                        "foo.requestDuration#count{" +
                        "httpMethod=TRACE,httpStatus=200,method=traceFoo,path=/foo,tservice=Example}"));
    }

    @Test
    void hasSameNameAndTagAsDefaultMeterIdPrefixFunction() {
        final MeterRegistry registry = NoopMeterRegistry.get();
        final MeterIdPrefixFunction f1 = RetrofitMeterIdPrefixFunction.of("foo");
        final MeterIdPrefixFunction f2 = MeterIdPrefixFunction.ofDefault("foo");

        final ClientRequestContext ctx = newContext();
        assertThat(f1.apply(registry, ctx.log())).isEqualTo(f2.apply(registry, ctx.log()));
    }

    @ParameterizedTest
    @MethodSource
    void canParseAllRetrofitAnnotations(String method, List<Tag> expectedTags) {
        final Map<String, List<Tag>> methodToTags =
                RetrofitClassAwareMeterIdPrefixFunction.defineTagsForMethods(Example.class);

        assertThat(methodToTags).containsKey(method);
        assertThat(methodToTags.get(method)).containsExactlyInAnyOrderElementsOf(expectedTags);
    }

    private static Stream<Arguments> canParseAllRetrofitAnnotations() {
        return Stream.of(
                createArgumentsWithMethodAndTags("deleteFoo", "DELETE", "/foo"),
                createArgumentsWithMethodAndTags("getFoo", "GET", "/foo"),
                createArgumentsWithMethodAndTags("headFoo", "HEAD", "/foo"),
                createArgumentsWithMethodAndTags("traceFoo", "TRACE", "/foo"),
                createArgumentsWithMethodAndTags("optionsFoo", "OPTIONS", "/foo"),
                createArgumentsWithMethodAndTags("patchFoo", "PATCH", "/foo"),
                createArgumentsWithMethodAndTags("postFoo", "POST", "/foo"),
                createArgumentsWithMethodAndTags("putFoo", "PUT", "/foo")
        );
    }

    private static Arguments createArgumentsWithMethodAndTags(String methodName,
                                                              String httpMethod,
                                                              String path) {
        return Arguments.of(
                methodName,
                ImmutableList.of(
                        Tag.of("httpMethod", httpMethod),
                        Tag.of("method", methodName),
                        Tag.of("path", path))
        );
    }

    private static ClientRequestContext newContext() {
        final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        ctx.logBuilder().endRequest();
        return ctx;
    }
}
