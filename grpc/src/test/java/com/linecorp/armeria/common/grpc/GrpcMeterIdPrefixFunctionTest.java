/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.armeria.common.grpc;

import static com.linecorp.armeria.common.metric.MoreMeters.measureAll;
import static io.micrometer.core.instrument.Statistic.COUNT;
import static io.micrometer.core.instrument.Statistic.TOTAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.awaitility.Awaitility.given;

import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.client.metric.MetricCollectingClient;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.common.logging.RequestLogAccess;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.common.prometheus.PrometheusMeterRegistries;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.Statistic;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import testing.grpc.EmptyProtos.Empty;
import testing.grpc.Messages.SimpleRequest;
import testing.grpc.Messages.SimpleResponse;
import testing.grpc.TestServiceGrpc;
import testing.grpc.TestServiceGrpc.TestServiceBlockingStub;

class GrpcMeterIdPrefixFunctionTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service(GrpcService.builder()
                                  .addService(new TestServiceImpl())
                                  .build());
        }
    };

    @Nullable
    private ClientFactory clientFactory;

    @AfterEach
    void tearDown() {
        if (clientFactory != null) {
            clientFactory.closeAsync();
        }
    }

    @ArgumentsSource(GrpcSerializationFormatArgumentSource.class)
    @ParameterizedTest
    void emptyCall_trailersOnly(SerializationFormat serializationFormat) {
        final PrometheusMeterRegistry registry = PrometheusMeterRegistries.newRegistry();
        final TestServiceBlockingStub client = newClient(serializationFormat, registry);

        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            //noinspection ResultOfMethodCallIgnored
            assertThatThrownBy(() -> client.emptyCall(Empty.getDefaultInstance())).isExactlyInstanceOf(
                    StatusRuntimeException.class);
            final RequestLogAccess log = captor.get().log();
            await().until(() -> log.isAvailable(RequestLogProperty.RESPONSE_HEADERS));
            final ResponseHeaders responseHeaders = log.ensureAvailable(
                    RequestLogProperty.RESPONSE_HEADERS).responseHeaders();
            assertThat(responseHeaders.get(GrpcHeaderNames.GRPC_STATUS)).isEqualTo("10");
        }

        // Chance that get() returns NPE before the metric is first added, so ignore exceptions.
        given().ignoreExceptions().untilAsserted(() -> assertThat(
                findClientMeter(registry, "EmptyCall", "requests", COUNT,
                                "result", "success", "grpc.status", "10")).isEqualTo(0.0));
        given().ignoreExceptions().untilAsserted(() -> assertThat(
                findClientMeter(registry, "EmptyCall", "requests", COUNT,
                                "result", "failure", "grpc.status", "10")).isEqualTo(1.0));

        assertThat(findClientMeter(registry, "EmptyCall", "request.length", COUNT, "grpc.status", "10"))
                .isEqualTo(1.0);
        assertThat(findClientMeter(registry, "EmptyCall", "response.length", COUNT, "grpc.status", "10"))
                .isEqualTo(1.0);
    }

    @ArgumentsSource(GrpcSerializationFormatArgumentSource.class)
    @ParameterizedTest
    void unaryCall_trailers(SerializationFormat serializationFormat) {
        final PrometheusMeterRegistry registry = PrometheusMeterRegistries.newRegistry();
        final TestServiceBlockingStub client = newClient(serializationFormat, registry);

        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            //noinspection ResultOfMethodCallIgnored
            assertThatThrownBy(() -> client.unaryCall(SimpleRequest.getDefaultInstance())).isExactlyInstanceOf(
                    StatusRuntimeException.class);
            final ClientRequestContext ctx = captor.get();
            // A failed call returns a trailers-only response.
            final HttpHeaders trailers = ctx.log().whenComplete().join().responseHeaders();
            assertThat(trailers.get(GrpcHeaderNames.GRPC_STATUS)).isEqualTo("13");
        }

        // Chance that get() returns NPE before the metric is first added, so ignore exceptions.
        given().ignoreExceptions().untilAsserted(() -> assertThat(
                findClientMeter(registry, "UnaryCall", "requests", COUNT,
                                "result", "success", "grpc.status", "13")).isEqualTo(0.0));
        given().ignoreExceptions().untilAsserted(() -> assertThat(
                findClientMeter(registry, "UnaryCall", "requests", COUNT,
                                "result", "failure", "grpc.status", "13")).isEqualTo(1.0));

        assertThat(findClientMeter(registry, "UnaryCall", "request.length", COUNT, "grpc.status", "13"))
                .isEqualTo(1.0);
        assertThat(findClientMeter(registry, "UnaryCall", "request.length", TOTAL, "grpc.status", "13"))
                .isGreaterThan(0.0);

        assertThat(findClientMeter(registry, "UnaryCall", "response.length", COUNT, "grpc.status", "13"))
                .isEqualTo(1.0);
        // The Trailers-Only response won't contain data.
        assertThat(findClientMeter(registry, "UnaryCall", "response.length", TOTAL, "grpc.status", "13"))
                .isEqualTo(0.0);
    }

    @ArgumentsSource(GrpcSerializationFormatArgumentSource.class)
    @ParameterizedTest
    void unaryCall2(SerializationFormat serializationFormat) {
        final PrometheusMeterRegistry registry = PrometheusMeterRegistries.newRegistry();
        final TestServiceBlockingStub client = newClient(serializationFormat, registry);

        assertThat(client.unaryCall2(SimpleRequest.getDefaultInstance()))
                .isEqualTo(SimpleResponse.getDefaultInstance());

        // Chance that get() returns NPE before the metric is first added, so ignore exceptions.
        given().ignoreExceptions().untilAsserted(() -> assertThat(
                findClientMeter(registry, "UnaryCall2", "requests", COUNT,
                                "result", "success", "grpc.status", "0")).isEqualTo(1.0));
        given().ignoreExceptions().untilAsserted(() -> assertThat(
                findClientMeter(registry, "UnaryCall2", "requests", COUNT,
                                "result", "failure", "grpc.status", "0")).isEqualTo(0.0));

        assertThat(findClientMeter(registry, "UnaryCall2", "request.length", COUNT, "grpc.status", "0"))
                .isEqualTo(1.0);
        assertThat(findClientMeter(registry, "UnaryCall2", "request.length", TOTAL, "grpc.status", "0"))
                .isGreaterThan(0.0);

        assertThat(findClientMeter(registry, "UnaryCall2", "response.length", COUNT, "grpc.status", "0"))
                .isEqualTo(1.0);
        assertThat(findClientMeter(registry, "UnaryCall2", "response.length", TOTAL, "grpc.status", "0"))
                .isGreaterThan(0.0);
    }

    private TestServiceBlockingStub newClient(SerializationFormat serializationFormat,
                                              PrometheusMeterRegistry registry) {
        clientFactory = ClientFactory.builder().meterRegistry(registry).build();
        return GrpcClients.builder(server.uri(SessionProtocol.H1C, serializationFormat))
                          .factory(clientFactory)
                          .decorator(MetricCollectingClient.newDecorator(
                                  GrpcMeterIdPrefixFunction.of("client")))
                          .build(TestServiceBlockingStub.class);
    }

    @Nullable
    private static Double findClientMeter(
            PrometheusMeterRegistry registry, String method, String suffix,
            Statistic type, String... keyValues) {
        final MeterIdPrefix prefix = new MeterIdPrefix(
                "client." + suffix + '#' + type.getTagValueRepresentation(),
                "service", "armeria.grpc.testing.TestService",
                "method", method,
                "http.status", "200");
        final String meterIdStr = prefix.withTags(keyValues).toString();
        return measureAll(registry).get(meterIdStr);
    }

    private static class GrpcSerializationFormatArgumentSource implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(final ExtensionContext context) throws Exception {
            return GrpcSerializationFormats.values().stream().map(Arguments::of);
        }
    }

    private static class TestServiceImpl extends TestServiceGrpc.TestServiceImplBase {
        @Override
        public void emptyCall(Empty request, StreamObserver<Empty> responseObserver) {
            responseObserver.onError(new StatusException(Status.ABORTED));
        }

        @Override
        public void unaryCall(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            responseObserver.onNext(SimpleResponse.getDefaultInstance());
            responseObserver.onError(new StatusException(Status.INTERNAL));
        }

        @Override
        public void unaryCall2(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            responseObserver.onNext(SimpleResponse.getDefaultInstance());
            responseObserver.onCompleted();
        }
    }
}
