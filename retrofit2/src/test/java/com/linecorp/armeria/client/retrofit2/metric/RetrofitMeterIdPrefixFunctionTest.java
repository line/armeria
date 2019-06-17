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
package com.linecorp.armeria.client.retrofit2.metric;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientFactoryBuilder;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.metric.MetricCollectingClient;
import com.linecorp.armeria.client.retrofit2.ArmeriaRetrofitBuilder;
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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import retrofit2.http.GET;
import retrofit2.http.POST;

public class RetrofitMeterIdPrefixFunctionTest {

    private static final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private static final ClientFactory clientFactory = new ClientFactoryBuilder().meterRegistry(meterRegistry)
                                                                                 .build();

    interface Example {
        @GET("/foo")
        CompletableFuture<Void> getFoo();

        @POST("/foo")
        CompletableFuture<Void> postFoo();
    }

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/foo", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                    return HttpResponse.of(HttpStatus.OK);
                }

                @Override
                protected HttpResponse doPost(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                    return HttpResponse.of(HttpStatus.OK);
                }
            });
        }
    };

    @Test
    public void metrics() {
        final Example example = new ArmeriaRetrofitBuilder(clientFactory)
                .baseUrl("h1c://127.0.0.1:" + server.httpPort())
                .withClientOptions((s, clientOptionsBuilder) -> {
                    return clientOptionsBuilder.decorator(
                            MetricCollectingClient.newDecorator(
                                    RetrofitMeterIdPrefixFunctionBuilder.ofName("foo").build()));
                })
                .build()
                .create(Example.class);

        example.getFoo().join();
        await().untilAsserted(() -> assertThat(MoreMeters.measureAll(meterRegistry))
                .containsKeys("foo.activeRequests#value{method=getFoo}",
                              "foo.requestDuration#count{httpStatus=200,method=getFoo}"));

        example.postFoo().join();
        await().untilAsserted(() -> assertThat(MoreMeters.measureAll(meterRegistry))
                .containsKeys("foo.activeRequests#value{method=postFoo}",
                              "foo.requestDuration#count{httpStatus=200,method=postFoo}"));
    }

    @Test
    public void metrics_withServiceTag() {
        final Example example = new ArmeriaRetrofitBuilder(clientFactory)
                .baseUrl("h1c://127.0.0.1:" + server.httpPort())
                .withClientOptions((s, clientOptionsBuilder) -> {
                    return clientOptionsBuilder.decorator(
                            MetricCollectingClient.newDecorator(
                                    RetrofitMeterIdPrefixFunctionBuilder
                                            .ofName("foo")
                                            .withServiceTag("service", "fallbackService")
                                            .build()));
                })
                .build()
                .create(Example.class);

        example.getFoo().join();
        await().untilAsserted(() -> assertThat(MoreMeters.measureAll(meterRegistry))
                .containsKeys("foo.activeRequests#value{method=getFoo,service=Example}",
                              "foo.requestDuration#count{httpStatus=200,method=getFoo,service=Example}"));

        example.postFoo().join();
        await().untilAsserted(() -> assertThat(MoreMeters.measureAll(meterRegistry))
                .containsKeys("foo.activeRequests#value{method=postFoo,service=Example}",
                              "foo.requestDuration#count{httpStatus=200,method=postFoo,service=Example}"));
    }

    @Test
    public void hasSameNameAndTagAsDefaultMeterIdPrefixFunction() {
        final MeterRegistry registry = NoopMeterRegistry.get();
        final MeterIdPrefixFunction f1 = RetrofitMeterIdPrefixFunctionBuilder.ofName("foo").build();
        final MeterIdPrefixFunction f2 = MeterIdPrefixFunction.ofDefault("foo");

        final ClientRequestContext ctx = newContext();
        assertThat(f1.apply(registry, ctx.log())).isEqualTo(f2.apply(registry, ctx.log()));
    }

    private static ClientRequestContext newContext() {
        final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        ctx.logBuilder().endRequest();
        return ctx;
    }
}
