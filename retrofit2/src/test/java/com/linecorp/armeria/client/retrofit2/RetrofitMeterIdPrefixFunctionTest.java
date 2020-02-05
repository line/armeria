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

import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.metric.MetricCollectingClient;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;
import com.linecorp.armeria.common.metric.MoreMeters;
import com.linecorp.armeria.common.metric.NoopMeterRegistry;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit.server.ServerExtension;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import retrofit2.http.GET;
import retrofit2.http.POST;

class RetrofitMeterIdPrefixFunctionTest {

    private static final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private static final ClientFactory clientFactory = ClientFactory.builder()
                                                                    .meterRegistry(meterRegistry)
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
        protected void configure(ServerBuilder sb) {
            sb.service("/foo", (ctx, req) -> HttpResponse.of(HttpStatus.OK));
        }
    };

    @ParameterizedTest
    @MethodSource
    void metrics(RetrofitMeterIdPrefixFunction meterIdPrefixFunction, String serviceTag) {
        final Example example = ArmeriaRetrofit
                .of(WebClient.builder(server.httpUri())
                             .factory(clientFactory)
                             .decorator(MetricCollectingClient.newDecorator(meterIdPrefixFunction))
                             .build())
                .create(Example.class);

        example.getFoo().join();
        await().untilAsserted(() -> assertThat(MoreMeters.measureAll(meterRegistry))
                .containsKeys("foo.active.requests#value{method=getFoo}",
                              "foo.request.duration#count{http.status=200,method=getFoo" + serviceTag + '}'));

        example.postFoo().join();
        await().untilAsserted(() -> assertThat(MoreMeters.measureAll(meterRegistry))
                .containsKeys("foo.active.requests#value{method=postFoo}",
                              "foo.request.duration#count{http.status=200,method=postFoo" + serviceTag + '}'));
    }

    private static Stream<Arguments> metrics() {
        return Stream.of(
                Arguments.of(RetrofitMeterIdPrefixFunction
                                     .of("foo"),
                             ""),
                Arguments.of(RetrofitMeterIdPrefixFunction
                                     .builder("foo")
                                     .withServiceTag("tservice", "fallbackService")
                                     .build(),
                             ",tservice=Example"),
                Arguments.of(RetrofitMeterIdPrefixFunction
                                     .builder("foo")
                                     .serviceTag("tservice")
                                     .build(),
                             ",tservice=Example"),
                Arguments.of(RetrofitMeterIdPrefixFunction
                                     .builder("foo")
                                     .serviceTag("tservice")
                                     .serviceName("serviceName")
                                     .build(),
                             ",tservice=serviceName"),
                Arguments.of(RetrofitMeterIdPrefixFunction
                                     .builder("foo")
                                     .withServiceTag("serviceTagName", "defaultServiceName")
                                     .serviceName("serviceName")
                                     .build(),
                             ",serviceTagName=serviceName")
        );
    }

    @Test
    void hasSameNameAndTagAsDefaultMeterIdPrefixFunction() {
        final MeterRegistry registry = NoopMeterRegistry.get();
        final MeterIdPrefixFunction f1 = RetrofitMeterIdPrefixFunction.of("foo");
        final MeterIdPrefixFunction f2 = MeterIdPrefixFunction.ofDefault("foo");

        final RequestLog log = newContext().log().ensureComplete();
        assertThat(f1.completeRequestPrefix(registry, log)).isEqualTo(f2.completeRequestPrefix(registry, log));
    }

    private static ClientRequestContext newContext() {
        final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        ctx.logBuilder().endRequest();
        ctx.logBuilder().endResponse();
        return ctx;
    }
}
