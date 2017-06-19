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

import java.util.Collection;
import java.util.concurrent.TimeUnit;

import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.DisableOnDebug;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.ByteString;

import com.linecorp.armeria.client.ClientBuilder;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.metric.MetricCollectingClient;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.common.metric.BuiltInMetricLabel;
import com.linecorp.armeria.common.metric.MetricKey;
import com.linecorp.armeria.common.metric.MetricKeyFunction;
import com.linecorp.armeria.common.metric.Metrics;
import com.linecorp.armeria.common.metric.RequestMetrics;
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

public class GrpcMetricsIntegrationTest {

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
            sb.port(0, SessionProtocol.HTTP);
            sb.serviceUnder("/", new GrpcServiceBuilder()
                         .addService(new TestServiceImpl())
                         .enableUnframedRequests(true)
                         .build()
                         .decorate(MetricCollectingService.newDecorator(
                                 MetricKeyFunction.ofDefault("request"))));
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
                serverRequestMetrics("UnaryCall").success().value() +
                serverRequestMetrics("UnaryCall").failure().value()).isEqualTo(7));
        given().ignoreExceptions().untilAsserted(() -> assertThat(
                clientRequestMetrics("UnaryCall").success().value() +
                clientRequestMetrics("UnaryCall").failure().value()).isEqualTo(7));

        final RequestMetrics srm = serverRequestMetrics("UnaryCall");
        final RequestMetrics crm = clientRequestMetrics("UnaryCall");

        assertThat(srm.success().value()).isEqualTo(4);
        assertThat(crm.success().value()).isEqualTo(4);
        assertThat(srm.failure().value()).isEqualTo(3);
        assertThat(crm.failure().value()).isEqualTo(3);

        assertThat(srm.requestLength().snapshot().min()).isEqualTo(14);
        assertThat(srm.requestLength().snapshot().max()).isEqualTo(14);
        assertThat(crm.requestLength().snapshot().min()).isEqualTo(14);
        assertThat(crm.requestLength().snapshot().max()).isEqualTo(14);
        assertThat(srm.responseLength().snapshot().min()).isEqualTo(0);
        assertThat(srm.responseLength().snapshot().max()).isEqualTo(5);
        assertThat(crm.responseLength().snapshot().min()).isEqualTo(0);
        assertThat(crm.responseLength().snapshot().max()).isEqualTo(5);
    }

    private static RequestMetrics serverRequestMetrics(String method) {
        final MetricKey key = new MetricKey(ImmutableList.of("request"),
                                            ImmutableMap.of(BuiltInMetricLabel.method,
                                                            "armeria.grpc.testing.TestService/" + method,
                                                            BuiltInMetricLabel.pathMapping,
                                                            "catch-all"));
        return requestMetrics(server.server().metrics(), key);
    }

    private static RequestMetrics clientRequestMetrics(String method) {
        final MetricKey key = new MetricKey(ImmutableList.of("request"),
                                            ImmutableMap.of(BuiltInMetricLabel.method,
                                                            "armeria.grpc.testing.TestService/" + method));
        return requestMetrics(ClientFactory.DEFAULT.metrics(), key);
    }

    private static RequestMetrics requestMetrics(Metrics metrics, MetricKey key) {
        final Collection<RequestMetrics> groups = metrics.groups(key, RequestMetrics.class);
        assertThat(groups).hasSize(1);
        return groups.iterator().next();
    }

    private static void makeRequest(String name) throws Exception {
        TestServiceBlockingStub client = new ClientBuilder(server.uri(GrpcSerializationFormats.PROTO, "/"))
                .decorator(HttpRequest.class, HttpResponse.class,
                           MetricCollectingClient.newDecorator(
                                   MetricKeyFunction.ofDefault("request")))
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
