/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.it.grpc;

import static com.linecorp.armeria.common.metric.MeterRegistryUtil.measure;
import static com.linecorp.armeria.common.metric.MeterRegistryUtil.measureAll;
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

import com.google.common.collect.Iterables;
import com.google.protobuf.ByteString;

import com.linecorp.armeria.client.ClientBuilder;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientFactoryBuilder;
import com.linecorp.armeria.client.metric.MetricCollectingClient;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.common.metric.MeterIdFunction;
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
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.prometheus.PrometheusMeterRegistry;
import io.micrometer.core.instrument.util.MeterId;

public class GrpcMetricsIntegrationTest {

    private static final MeterRegistry registry = new PrometheusMeterRegistry();

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
                                 MeterIdFunction.ofDefault("server"))));
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
                measure(registry, serverMeterId("UnaryCall", "requests_total", "result", "success")) +
                measure(registry, serverMeterId("UnaryCall", "requests_total", "result", "failure")))
                .isEqualTo(7));
        given().ignoreExceptions().untilAsserted(() -> assertThat(
                measure(registry, clientMeterId("UnaryCall", "requests_total", "result", "success")) +
                measure(registry, clientMeterId("UnaryCall", "requests_total", "result", "failure")))
                .isEqualTo(7));

        assertThat(measure(registry, serverMeterId("UnaryCall", "requests_total", "result", "success")))
                .isEqualTo(4);
        assertThat(measure(registry, clientMeterId("UnaryCall", "requests_total", "result", "success")))
                .isEqualTo(4);
        assertThat(measure(registry, serverMeterId("UnaryCall", "requests_total", "result", "failure")))
                .isEqualTo(3);
        assertThat(measure(registry, clientMeterId("UnaryCall", "requests_total", "result", "failure")))
                .isEqualTo(3);

        assertThat(measureAll(registry, serverMeterId("UnaryCall", "request_length_bytes")).get("count"))
                .isEqualTo(7);
        assertThat(measureAll(registry, serverMeterId("UnaryCall", "request_length_bytes")).get("sum"))
                .isEqualTo(7 * 14);
        assertThat(measureAll(registry, clientMeterId("UnaryCall", "request_length_bytes")).get("count"))
                .isEqualTo(7);
        assertThat(measureAll(registry, clientMeterId("UnaryCall", "request_length_bytes")).get("sum"))
                .isEqualTo(7 * 14);
        assertThat(measureAll(registry, serverMeterId("UnaryCall", "response_length_bytes")).get("count"))
                .isEqualTo(7);
        assertThat(measureAll(registry, serverMeterId("UnaryCall", "response_length_bytes")).get("sum"))
                .isEqualTo(4 * 5 /* + 3 * 0 */);
        assertThat(measureAll(registry, clientMeterId("UnaryCall", "response_length_bytes")).get("count"))
                .isEqualTo(7);
        assertThat(measureAll(registry, clientMeterId("UnaryCall", "response_length_bytes")).get("sum"))
                .isEqualTo(4 * 5 /* + 3 * 0 */);
    }

    private static MeterId serverMeterId(String method, String suffix, String... keyValues) {
        return new MeterId("server_" + suffix,
                           Iterables.concat(Tags.zip("method", "armeria.grpc.testing.TestService/" + method,
                                                     "pathMapping", "catch-all"),
                                            Tags.zip(keyValues)));
    }

    private static MeterId clientMeterId(String method, String suffix, String... keyValues) {
        return new MeterId("client_" + suffix,
                           Iterables.concat(Tags.zip("method", "armeria.grpc.testing.TestService/" + method),
                                            Tags.zip(keyValues)));
    }

    private static void makeRequest(String name) throws Exception {
        TestServiceBlockingStub client = new ClientBuilder(server.uri(GrpcSerializationFormats.PROTO, "/"))
                .factory(clientFactory)
                .decorator(HttpRequest.class, HttpResponse.class,
                           MetricCollectingClient.newDecorator(
                                   MeterIdFunction.ofDefault("client")))
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
