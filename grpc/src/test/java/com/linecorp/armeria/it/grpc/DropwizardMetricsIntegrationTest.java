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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.given;

import java.util.concurrent.TimeUnit;

import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.DisableOnDebug;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;

import com.codahale.metrics.MetricRegistry;
import com.google.protobuf.ByteString;

import com.linecorp.armeria.client.ClientBuilder;
import com.linecorp.armeria.client.metric.DropwizardMetricCollectingClient;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.grpc.testing.Messages.Payload;
import com.linecorp.armeria.grpc.testing.Messages.SimpleRequest;
import com.linecorp.armeria.grpc.testing.Messages.SimpleResponse;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc.TestServiceBlockingStub;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc.TestServiceImplBase;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcServiceBuilder;
import com.linecorp.armeria.server.metric.DropwizardMetricCollectingService;
import com.linecorp.armeria.testing.server.ServerRule;

import io.grpc.stub.StreamObserver;

public class DropwizardMetricsIntegrationTest {

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

    private static final MetricRegistry metricRegistry = new MetricRegistry();

    @ClassRule
    public static final ServerRule server = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.port(0, SessionProtocol.HTTP);
            sb.serviceUnder("/", new GrpcServiceBuilder()
                         .addService(new TestServiceImpl())
                         .enableUnframedRequests(true)
                         .build()
                         .decorate(DropwizardMetricCollectingService.newDecorator(
                                 metricRegistry, MetricRegistry.name("services"))));
        }
    };

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
                metricRegistry.getTimers().get(serverMetricName("UnaryCall", "requests")).getCount())
                .isEqualTo(7));
        given().ignoreExceptions().untilAsserted(() -> assertThat(
                metricRegistry.getTimers().get(clientMetricName("UnaryCall", "requests")).getCount())
                .isEqualTo(7));
        assertThat(metricRegistry.getMeters().get(serverMetricName("UnaryCall", "successes")).getCount())
                .isEqualTo(4);
        assertThat(metricRegistry.getMeters().get(clientMetricName("UnaryCall", "successes")).getCount())
                .isEqualTo(4);
        assertThat(metricRegistry.getMeters().get(serverMetricName("UnaryCall", "failures")).getCount())
                .isEqualTo(3);
        assertThat(metricRegistry.getMeters().get(clientMetricName("UnaryCall", "failures")).getCount())
                .isEqualTo(3);
        assertThat(metricRegistry.getMeters().get(serverMetricName("UnaryCall", "requestBytes")).getCount())
                .isEqualTo(98);
        assertThat(metricRegistry.getMeters().get(clientMetricName("UnaryCall", "requestBytes")).getCount())
                .isEqualTo(98);
        assertThat(metricRegistry.getMeters().get(serverMetricName("UnaryCall", "responseBytes")).getCount())
                .isEqualTo(20);
        assertThat(metricRegistry.getMeters().get(clientMetricName("UnaryCall", "responseBytes")).getCount())
                .isEqualTo(20);
    }

    private static String serverMetricName(String method, String property) {
        return MetricRegistry.name("services",
                                   "/**",
                                   "armeria.grpc.testing.TestService/" + method, property);
    }

    private static String clientMetricName(String method, String property) {
        return MetricRegistry.name("clients",
                                   "armeria.grpc.testing.TestService/" + method, property);
    }

    private static void makeRequest(String name) throws Exception {
        TestServiceBlockingStub client = new ClientBuilder(server.uri(GrpcSerializationFormats.PROTO, "/"))
                .decorator(HttpRequest.class, HttpResponse.class,
                           DropwizardMetricCollectingClient.newDecorator(
                                   metricRegistry, MetricRegistry.name("clients")))
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
