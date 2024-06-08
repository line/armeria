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

import static io.micrometer.core.instrument.Statistic.COUNT;
import static io.micrometer.core.instrument.Statistic.TOTAL;
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

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.client.metric.MetricCollectingClient;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.grpc.GrpcMeterIdPrefixFunction;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.common.metric.MoreMeters;
import com.linecorp.armeria.common.prometheus.PrometheusMeterRegistries;
import com.linecorp.armeria.internal.testing.GenerateNativeImageTrace;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.server.metric.MetricCollectingService;
import com.linecorp.armeria.testing.junit4.server.ServerRule;

import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Statistic;
import testing.grpc.Messages.Payload;
import testing.grpc.Messages.SimpleRequest;
import testing.grpc.Messages.SimpleResponse;
import testing.grpc.TestServiceGrpc.TestServiceBlockingStub;
import testing.grpc.TestServiceGrpc.TestServiceImplBase;

@GenerateNativeImageTrace
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

        @Override
        public void unaryCall2(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
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
            sb.service(GrpcService.builder()
                                  .addService(new TestServiceImpl())
                                  .enableUnframedRequests(true)
                                  .build(),
                       MetricCollectingService.newDecorator(GrpcMeterIdPrefixFunction.of("server")),
                       LoggingService.newDecorator());
            sb.verboseResponses(false);
        }
    };

    private static final ClientFactory clientFactory =
            ClientFactory.builder().meterRegistry(registry).build();

    @AfterClass
    public static void closeClientFactory() {
        clientFactory.closeAsync();
    }

    @Rule
    public final TestRule globalTimeout = new DisableOnDebug(new Timeout(30, TimeUnit.SECONDS));

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
                findServerMeter("UnaryCall", "requests", COUNT,
                                "result", "success", "http.status", "200", "grpc.status", "0")).isEqualTo(4.0));
        given().ignoreExceptions().untilAsserted(() -> assertThat(
                findServerMeter("UnaryCall", "requests", COUNT,
                                "result", "failure", "http.status", "200", "grpc.status", "2")).isEqualTo(3.0));
        given().ignoreExceptions().untilAsserted(() -> assertThat(
                findClientMeter("UnaryCall", "requests", COUNT,
                                "result", "success", "grpc.status", "0")).isEqualTo(4.0));
        given().ignoreExceptions().untilAsserted(() -> assertThat(
                findClientMeter("UnaryCall", "requests", COUNT,
                                "result", "failure", "grpc.status", "2")).isEqualTo(3.0));

        assertThat(findServerMeter("UnaryCall", "request.length", COUNT,
                                   "http.status", "200", "grpc.status", "0")).isEqualTo(4.0);
        assertThat(findServerMeter("UnaryCall", "request.length", COUNT,
                                   "http.status", "200", "grpc.status", "2")).isEqualTo(3.0);
        assertThat(findServerMeter("UnaryCall", "request.length", TOTAL,
                                   "http.status", "200", "grpc.status", "0")).isEqualTo(4.0 * 14);
        assertThat(findServerMeter("UnaryCall", "request.length", TOTAL,
                                   "http.status", "200", "grpc.status", "2")).isEqualTo(3.0 * 14);
        assertThat(findClientMeter("UnaryCall", "request.length", COUNT, "grpc.status", "0")).isEqualTo(4.0);
        assertThat(findClientMeter("UnaryCall", "request.length", COUNT, "grpc.status", "2")).isEqualTo(3.0);
        assertThat(findClientMeter("UnaryCall", "request.length", TOTAL, "grpc.status", "0"))
                .isEqualTo(4.0 * 14);
        assertThat(findClientMeter("UnaryCall", "request.length", TOTAL, "grpc.status", "2"))
                .isEqualTo(3.0 * 14);
        assertThat(findServerMeter("UnaryCall", "response.length", COUNT,
                                   "http.status", "200", "grpc.status", "0")).isEqualTo(4.0);
        assertThat(findServerMeter("UnaryCall", "response.length", COUNT,
                                   "http.status", "200", "grpc.status", "2")).isEqualTo(3.0);
        assertThat(findServerMeter("UnaryCall", "response.length", TOTAL,
                                   "http.status", "200", "grpc.status", "0")).isEqualTo(4.0 * 5);
        // Failed calls do not have response length.

        assertThat(findClientMeter("UnaryCall", "response.length", COUNT, "grpc.status", "0")).isEqualTo(4.0);
        assertThat(findClientMeter("UnaryCall", "response.length", COUNT, "grpc.status", "2")).isEqualTo(3.0);
        assertThat(findClientMeter("UnaryCall", "response.length", TOTAL, "grpc.status", "0"))
                .isEqualTo(4.0 * 5);
        // Failed calls do not have response length.
    }

    @Test
    public void unframed() throws Exception {
        makeUnframedRequest("world");
        makeUnframedRequest("world");
        makeUnframedRequest("space");
        makeUnframedRequest("world");
        makeUnframedRequest("space");
        makeUnframedRequest("space");
        makeUnframedRequest("world");

        // Chance that get() returns NPE before the metric is first added, so ignore exceptions.
        given().ignoreExceptions().untilAsserted(() -> assertThat(
                findServerMeter("UnaryCall2", "requests", COUNT,
                                "result", "success", "http.status", "200", "grpc.status", "0")).isEqualTo(4.0));
        given().ignoreExceptions().untilAsserted(() -> assertThat(
                findServerMeter("UnaryCall2", "requests", COUNT,
                                "result", "failure", "http.status", "500", "grpc.status", "2")).isEqualTo(3.0));

        assertThat(findServerMeter("UnaryCall2", "response.length", COUNT,
                                   "http.status", "200", "grpc.status", "0")).isEqualTo(4.0);
        assertThat(findServerMeter("UnaryCall2", "response.length", COUNT,
                                   "http.status", "500", "grpc.status", "2")).isEqualTo(3.0);
        assertThat(findServerMeter("UnaryCall2", "response.length", TOTAL,
                                   "http.status", "200", "grpc.status", "0")).isEqualTo(0.0);
        assertThat(findServerMeter("UnaryCall2", "response.length", TOTAL,
                                   "http.status", "500", "grpc.status", "2")).isEqualTo(54.0);
    }

    @Nullable
    private static Double findServerMeter(
            String method, String suffix, Statistic type, String... keyValues) {
        final MeterIdPrefix prefix = new MeterIdPrefix(
                "server." + suffix + '#' + type.getTagValueRepresentation(),
                "service", "armeria.grpc.testing.TestService",
                "method", method,
                "hostname.pattern", "*");
        final String meterIdStr = prefix.withTags(keyValues).toString();
        return MoreMeters.measureAll(registry).get(meterIdStr);
    }

    private static Double findClientMeter(
            String method, String suffix, Statistic type, String... keyValues) {
        final MeterIdPrefix prefix = new MeterIdPrefix(
                "client." + suffix + '#' + type.getTagValueRepresentation(),
                "service", "armeria.grpc.testing.TestService",
                "method", method,
                "http.status", "200");
        final String meterIdStr = prefix.withTags(keyValues).toString();
        return MoreMeters.measureAll(registry).get(meterIdStr);
    }

    private static void makeRequest(String name) throws Exception {
        final TestServiceBlockingStub client =
                GrpcClients.builder(server.httpUri())
                           .factory(clientFactory)
                           .decorator(MetricCollectingClient.newDecorator(
                                   GrpcMeterIdPrefixFunction.of("client")))
                           .build(TestServiceBlockingStub.class);

        final SimpleRequest request =
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

    private static void makeUnframedRequest(String name) throws Exception {
        final WebClient client =
                Clients.builder(server.httpUri())
                       .factory(clientFactory)
                       .addHeader(HttpHeaderNames.CONTENT_TYPE, MediaType.PROTOBUF.toString())
                       .build(WebClient.class);

        final SimpleRequest request =
                SimpleRequest.newBuilder()
                             .setPayload(Payload.newBuilder()
                                                .setBody(ByteString.copyFromUtf8(name)))
                             .build();
        try {
            client.post("/armeria.grpc.testing.TestService/UnaryCall2", request.toByteArray())
                  .aggregate();
        } catch (Throwable t) {
            // Ignore, we will count these up
        }
    }
}
