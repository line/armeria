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

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.metric.MetricCollectingClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.metric.MoreMeters;
import com.linecorp.armeria.internal.testing.GenerateNativeImageTrace;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import retrofit2.http.DELETE;
import retrofit2.http.GET;
import retrofit2.http.HEAD;
import retrofit2.http.HTTP;
import retrofit2.http.OPTIONS;
import retrofit2.http.PATCH;
import retrofit2.http.POST;
import retrofit2.http.PUT;

@GenerateNativeImageTrace
class RetrofitMeterIdPrefixFunctionTest {

    private static final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private static final ClientFactory clientFactory = ClientFactory.builder()
                                                                    .meterRegistry(meterRegistry)
                                                                    .build();

    private interface Example {
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
        protected void configure(ServerBuilder sb) {
            sb.service("/foo", (ctx, req) -> HttpResponse.of(HttpStatus.OK));
        }
    };

    @AfterAll
    static void closeClientFactory() {
        clientFactory.closeAsync();
    }

    @Test
    void metrics() {
        final RetrofitMeterIdPrefixFunction meterIdPrefixFunction = RetrofitMeterIdPrefixFunction.of("foo");
        final String serviceTag = "service=" + Example.class.getName();
        final Example example = ArmeriaRetrofit
                .of(WebClient.builder(server.httpUri())
                             .factory(clientFactory)
                             .decorator(MetricCollectingClient.newDecorator(meterIdPrefixFunction))
                             .build())
                .create(Example.class);

        example.getFoo().join();
        await().untilAsserted(() -> assertThat(MoreMeters.measureAll(meterRegistry))
                .containsKeys(
                        "foo.active.requests#value{http.method=GET,method=getFoo,path=/foo," + serviceTag + '}',
                        "foo.request.duration#count{" +
                        "http.method=GET,http.status=200,method=getFoo,path=/foo," + serviceTag + '}'));

        example.postFoo().join();
        await().untilAsserted(() -> assertThat(MoreMeters.measureAll(meterRegistry))
                .containsKeys(
                        "foo.active.requests#value{http.method=GET,method=getFoo,path=/foo," + serviceTag + '}',
                        "foo.request.duration#count{" +
                        "http.method=POST,http.status=200,method=postFoo,path=/foo," + serviceTag + '}'));

        example.traceFoo().join();
        await().untilAsserted(() -> assertThat(MoreMeters.measureAll(meterRegistry))
                .containsKeys(
                        "foo.active.requests#value{http.method=TRACE,method=traceFoo,path=/foo," +
                        serviceTag + '}',
                        "foo.request.duration#count{" +
                        "http.method=TRACE,http.status=200,method=traceFoo,path=/foo," + serviceTag + '}'));
    }

    @Test
    void canParseAllRetrofitAnnotations() {
        final Map<String, String> expected =
                ImmutableMap.<String, String>builder().put("deleteFoo", "/foo")
                                                      .put("getFoo", "/foo")
                                                      .put("headFoo", "/foo")
                                                      .put("traceFoo", "/foo")
                                                      .put("optionsFoo", "/foo")
                                                      .put("patchFoo", "/foo")
                                                      .put("postFoo", "/foo")
                                                      .put("putFoo", "/foo")
                                                      .build();
        final Map<String, String> actual =
                Arrays.stream(Example.class.getMethods())
                      .collect(toImmutableMap(Method::getName,
                                              RetrofitMeterIdPrefixFunction::getPathFromMethod));

        assertThat(actual).containsExactlyInAnyOrderEntriesOf(expected);
    }
}
