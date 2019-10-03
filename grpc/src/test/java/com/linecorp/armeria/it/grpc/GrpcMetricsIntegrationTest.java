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

import java.util.Optional;
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
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.metric.MetricCollectingClient;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;
import com.linecorp.armeria.common.metric.MoreMeters;
import com.linecorp.armeria.common.metric.PrometheusMeterRegistries;
import com.linecorp.armeria.grpc.testing.Messages.Payload;
import com.linecorp.armeria.grpc.testing.Messages.SimpleRequest;
import com.linecorp.armeria.grpc.testing.Messages.SimpleResponse;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc.TestServiceBlockingStub;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc.TestServiceImplBase;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.server.metric.MetricCollectingService;
import com.linecorp.armeria.testing.junit4.server.ServerRule;

import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.MeterRegistry;
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
                       MetricCollectingService.newDecorator(MeterIdPrefixFunction.ofDefault("server")),
                       LoggingService.newDecorator());
        }
    };

    private static final ClientFactory clientFactory =
            new ClientFactoryBuilder().meterRegistry(registry).build();

    @AfterClass
    public static void closeClientFactory() {
        clientFactory.close();
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
                findServerMeter("UnaryCall", "requests", COUNT, "result", "success", "httpStatus", "200"))
                .contains(4.0));
        given().ignoreExceptions().untilAsserted(() -> assertThat(
                findServerMeter("UnaryCall", "requests", COUNT, "result", "failure", "httpStatus", "200"))
                .contains(3.0));
        given().ignoreExceptions().untilAsserted(() -> assertThat(
                findClientMeter("UnaryCall", "requests", COUNT, "result", "success")).contains(4.0));
        given().ignoreExceptions().untilAsserted(() -> assertThat(
                findClientMeter("UnaryCall", "requests", COUNT, "result", "failure")).contains(3.0));

        assertThat(findServerMeter("UnaryCall", "requestLength", COUNT, "httpStatus", "200")).contains(7.0);
        assertThat(findServerMeter("UnaryCall", "requestLength", TOTAL, "httpStatus", "200"))
                .contains(7.0 * 14);
        assertThat(findClientMeter("UnaryCall", "requestLength", COUNT)).contains(7.0);
        assertThat(findClientMeter("UnaryCall", "requestLength", TOTAL)).contains(7.0 * 14);
        assertThat(findServerMeter("UnaryCall", "responseLength", COUNT, "httpStatus", "200")).contains(7.0);
        assertThat(findServerMeter("UnaryCall", "responseLength", TOTAL, "httpStatus", "200"))
                .contains(4.0 * 5 /* + 3 * 0 */);
        assertThat(findClientMeter("UnaryCall", "responseLength", COUNT)).contains(7.0);
        assertThat(findClientMeter("UnaryCall", "responseLength", TOTAL)).contains(4.0 * 5 /* + 3 * 0 */);
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
                findServerMeter("UnaryCall2", "requests", COUNT, "result", "success", "httpStatus", "200"))
                .contains(4.0));
        given().ignoreExceptions().untilAsserted(() -> assertThat(
                findServerMeter("UnaryCall2", "requests", COUNT, "result", "failure", "httpStatus", "500"))
                .contains(3.0));
        assertThat(findServerMeter("UnaryCall2", "responseLength", COUNT, "httpStatus", "200")).contains(4.0);
        assertThat(findServerMeter("UnaryCall2", "responseLength", COUNT, "httpStatus", "500")).contains(3.0);
        assertThat(findServerMeter("UnaryCall2", "responseLength", TOTAL, "httpStatus", "200")).contains(0.0);
        assertThat(findServerMeter("UnaryCall2", "responseLength", TOTAL, "httpStatus", "500")).contains(225.0);
    }

    private static Optional<Double> findServerMeter(
            String method, String suffix, Statistic type, String... keyValues) {
        final MeterIdPrefix prefix = new MeterIdPrefix(
                "server." + suffix + '#' + type.getTagValueRepresentation(),
                "method", "armeria.grpc.testing.TestService/" + method,
                "hostnamePattern", "*",
                "route", "exact:/armeria.grpc.testing.TestService/" + method);
        final String meterIdStr = prefix.withTags(keyValues).toString();
        return Optional.ofNullable(MoreMeters.measureAll(registry).get(meterIdStr));
    }

    private static Optional<Double> findClientMeter(
            String method, String suffix, Statistic type, String... keyValues) {
        final MeterIdPrefix prefix = new MeterIdPrefix(
                "client." + suffix + '#' + type.getTagValueRepresentation(),
                "method", "armeria.grpc.testing.TestService/" + method,
                "httpStatus", "200");
        final String meterIdStr = prefix.withTags(keyValues).toString();
        return Optional.ofNullable(MoreMeters.measureAll(registry).get(meterIdStr));
    }

    private static void makeRequest(String name) throws Exception {
        final String uri = server.uri(GrpcSerializationFormats.PROTO, "/");
        final TestServiceBlockingStub client = new ClientBuilder(uri)
                .factory(clientFactory)
                .decorator(MetricCollectingClient.newDecorator(MeterIdPrefixFunction.ofDefault("client")))
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
        final HttpClient client = new ClientBuilder(server.uri(SerializationFormat.NONE, "/"))
                .factory(clientFactory)
                .addHttpHeader(HttpHeaderNames.CONTENT_TYPE, MediaType.PROTOBUF.toString())
                .build(HttpClient.class);

        final SimpleRequest request =
                SimpleRequest.newBuilder()
                             .setPayload(Payload.newBuilder()
                                                .setBody(ByteString.copyFromUtf8(name)))
                             .build();
        try {
            client.post("/armeria.grpc.testing.TestService/UnaryCall2", request.toByteArray());
        } catch (Throwable t) {
            // Ignore, we will count these up
        }
    }
}
