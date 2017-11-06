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

package com.linecorp.armeria.it.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.given;

import java.util.concurrent.TimeUnit;

import org.junit.AfterClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.DisableOnDebug;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;

import com.google.protobuf.ByteString;

import com.linecorp.armeria.client.ClientBuilder;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientFactoryBuilder;
import com.linecorp.armeria.client.metric.MetricCollectingClient;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;
import com.linecorp.armeria.common.metric.PrometheusMeterRegistries;
import com.linecorp.armeria.grpc.testing.Messages.Payload;
import com.linecorp.armeria.grpc.testing.Messages.SimpleRequest;
import com.linecorp.armeria.grpc.testing.Messages.SimpleResponse;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc.TestServiceBlockingStub;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc.TestServiceImplBase;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcServiceBuilder;
import com.linecorp.armeria.server.metric.MetricCollectingService;
import com.linecorp.armeria.testing.server.ServerRule;

import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MeterRegistry.Search;
import io.micrometer.core.instrument.Statistic;

public class GrpcMetricsIntegrationTest {

    private static final MeterRegistry registry = PrometheusMeterRegistries.newRegistry();

    private static class TestServiceImpl extends TestServiceImplBase {
        @Override
        public void unaryCall(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            if ("world".equals(request.getPayload().getBody().toStringUtf8())) {
                responseObserver.onNext(SimpleResponse.getDefaultInstance());
                responseObserver.onCompleted();
                return;
            }
            responseObserver.onError(new IllegalArgumentException("bad argument"));
        }
    }

    @ClassRule
    public static final ServerRule server = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.meterRegistry(registry);
            sb.port(0, SessionProtocol.HTTP);
            sb.serviceUnder("/", new GrpcServiceBuilder()
                         .addService(new TestServiceImpl())
                         .enableUnframedRequests(true)
                         .build()
                         .decorate(MetricCollectingService.newDecorator(
                                 MeterIdPrefixFunction.ofDefault("server"))));
        }
    };

    private static final ClientFactory clientFactory =
            new ClientFactoryBuilder().meterRegistry(registry).build();

    @AfterClass
    public static void closeClientFactory() {
        clientFactory.close();
    }

    @Rule
    public TestRule globalTimeout = new DisableOnDebug(new Timeout(10, TimeUnit.SECONDS));

    @Test
    public void normal() throws Exception {
        makeRequest("world");
        makeRequest("world");
        makeRequest("space");
        makeRequest("world");
        makeRequest("space");
        makeRequest("space");
        makeRequest("world");

        // Chance that get() returns NPE before the metric is first added, so ignore exceptions.
        given().ignoreExceptions().untilAsserted(() -> assertThat(
                findServerMeter("UnaryCall", "requests", "result", "success")
                        .value(Statistic.Count, 4).meter()).isPresent());
        given().ignoreExceptions().untilAsserted(() -> assertThat(
                findServerMeter("UnaryCall", "requests", "result", "failure")
                        .value(Statistic.Count, 3).meter()).isPresent());
        given().ignoreExceptions().untilAsserted(() -> assertThat(
                findClientMeter("UnaryCall", "requests", "result", "success")
                        .value(Statistic.Count, 4).meter()).isPresent());
        given().ignoreExceptions().untilAsserted(() -> assertThat(
                findClientMeter("UnaryCall", "requests", "result", "failure")
                        .value(Statistic.Count, 3).meter()).isPresent());

        assertThat(findServerMeter("UnaryCall", "requestLength")
                           .value(Statistic.Count, 7).meter()).isPresent();
        assertThat(findServerMeter("UnaryCall", "requestLength")
                           .value(Statistic.Total, 7 * 14).meter()).isPresent();
        assertThat(findClientMeter("UnaryCall", "requestLength")
                           .value(Statistic.Count, 7).meter()).isPresent();
        assertThat(findClientMeter("UnaryCall", "requestLength")
                           .value(Statistic.Total, 7 * 14).meter()).isPresent();
        assertThat(findServerMeter("UnaryCall", "responseLength")
                           .value(Statistic.Count, 7).meter()).isPresent();
        assertThat(findServerMeter("UnaryCall", "responseLength")
                           .value(Statistic.Total, 4 * 5 /* + 3 * 0 */).meter()).isPresent();
        assertThat(findClientMeter("UnaryCall", "responseLength")
                           .value(Statistic.Count, 7).meter()).isPresent();
        assertThat(findClientMeter("UnaryCall", "responseLength")
                           .value(Statistic.Total, 4 * 5 /* + 3 * 0 */).meter()).isPresent();
    }

    private static Search findServerMeter(String method, String suffix, String... keyValues) {
        return registry.find("server." + suffix)
                       .tags("method", "armeria.grpc.testing.TestService/" + method,
                             "pathMapping", "catch-all")
                       .tags(keyValues);
    }

    private static Search findClientMeter(String method, String suffix, String... keyValues) {
        return registry.find("client." + suffix)
                       .tags("method", "armeria.grpc.testing.TestService/" + method)
                       .tags(keyValues);
    }

    private static void makeRequest(String name) throws Exception {
        TestServiceBlockingStub client = new ClientBuilder(server.uri(GrpcSerializationFormats.PROTO, "/"))
                .factory(clientFactory)
                .decorator(HttpRequest.class, HttpResponse.class,
                           MetricCollectingClient.newDecorator(
                                   MeterIdPrefixFunction.ofDefault("client")))
                .build(TestServiceBlockingStub.class);

        SimpleRequest request =
                SimpleRequest.newBuilder()
                             .setPayload(Payload.newBuilder()
                                                .setBody(ByteString.copyFromUtf8(name)))
                             .build();
        try {
            client.unaryCall(request);
        } catch (Throwable t) {
            // Ignore, we will count these up
        }
    }
}
