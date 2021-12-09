/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.server.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.grpc.testing.Messages.SimpleRequest;
import com.linecorp.armeria.grpc.testing.Messages.SimpleResponse;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc.TestServiceImplBase;
import com.linecorp.armeria.internal.common.grpc.StreamRecorder;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.healthcheck.SettableHealthChecker;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.health.v1.HealthGrpc.HealthBlockingStub;
import io.grpc.health.v1.HealthGrpc.HealthStub;
import io.grpc.stub.StreamObserver;

class GrpcHealthCheckServiceTest {

    static final SettableHealthChecker serveHealth = new SettableHealthChecker(true);

    static final GrpcHealthCheckService service = GrpcHealthCheckService
            .builder()
            .checkers(serveHealth)
            .checkerForGrpcService("com.linecorp.armeria.grpc.testing.TestService",
                                   new SettableHealthChecker(true))
            .build();

    @RegisterExtension
    static ServerExtension server = new ServerExtension(true) {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.requestTimeoutMillis(5000);
            sb.decorator(LoggingService.newDecorator());
            sb.service(GrpcService.builder()
                                  .addService(new TestServiceImpl())
                                  .grpcHealthCheckService(service)
                                  .build());
        }
    };

    @BeforeEach
    void setUp() {
        serveHealth.setHealthy(true);
    }

    @Test
    void check() {
        final HealthBlockingStub client = Clients.newClient(
                server.httpUri(GrpcSerializationFormats.PROTO),
                HealthBlockingStub.class);
        HealthCheckRequest request = HealthCheckRequest.getDefaultInstance();
        HealthCheckResponse response = client.check(request);
        assertThat(response.getStatus()).isEqualTo(ServingStatus.SERVING);

        request = HealthCheckRequest.newBuilder()
                                    .setService("com.linecorp.armeria.grpc.testing.TestService")
                                    .build();
        response = client.check(request);
        assertThat(response.getStatus()).isEqualTo(ServingStatus.SERVING);

        final StatusRuntimeException exception = assertThrows(StatusRuntimeException.class, () -> client.check(
                HealthCheckRequest.newBuilder()
                                  .setService("com.linecorp.armeria.grpc.testing.NotFoundTestService")
                                  .build()));
        assertThat(exception.getStatus().getCode()).isEqualTo(Status.NOT_FOUND.getCode());
        assertThat(exception.getMessage()).isEqualTo(
                "NOT_FOUND: The service name(=com.linecorp.armeria.grpc.testing.NotFoundTestService) is not registered in this service");
    }

    @Test
    void watch() throws Exception {
        final HealthCheckRequest request = HealthCheckRequest.getDefaultInstance();
        final HealthStub client = Clients.newClient(
                server.httpUri(GrpcSerializationFormats.PROTO),
                HealthStub.class);
        final StreamRecorder<HealthCheckResponse> recorder = StreamRecorder.create();
        client.watch(request, recorder);
        TimeUnit.SECONDS.sleep(1);
        serveHealth.setHealthy(false);
        TimeUnit.SECONDS.sleep(1);
        serveHealth.setHealthy(true);
        TimeUnit.SECONDS.sleep(1);
        recorder.onCompleted();
        final List<ServingStatus> responses = recorder.getValues()
                                                      .stream()
                                                      .map(HealthCheckResponse::getStatus)
                                                      .collect(Collectors.toList());
        assertThat(responses).containsExactlyElementsOf(
                ImmutableList.of(ServingStatus.SERVING, ServingStatus.NOT_SERVING, ServingStatus.SERVING));
    }

    @ParameterizedTest
    @MethodSource("checkServingStatusArguments")
    void checkServingStatus(GrpcHealthCheckService grpcHealthCheckService,
                            String serviceName,
                            boolean serverIsHealthy,
                            ServingStatus expected) throws Exception {
        grpcHealthCheckService.changeServerStatus(serverIsHealthy);
        assertThat(grpcHealthCheckService.checkServingStatus(serviceName)).isEqualTo(expected);
    }

    private static Stream<Arguments> checkServingStatusArguments() {
        return Stream.of(
                // request: empty server name
                // condition: server is healthy, health checker is not given.
                // response: SERVING
                Arguments.of(new GrpcHealthCheckService(
                                     ImmutableSet.of(),
                                     ImmutableMap.of(),
                                     ImmutableList.of()),
                             "",
                             true,
                             ServingStatus.SERVING),
                // request: empty server name
                // condition: server is unhealthy, health checker is not given.
                // response: NOT_SERVING
                Arguments.of(new GrpcHealthCheckService(
                                     ImmutableSet.of(),
                                     ImmutableMap.of(),
                                     ImmutableList.of()),
                             "",
                             false,
                             ServingStatus.NOT_SERVING),
                // request: com.linecorp.armeria.server.grpc.TestService (not registered)
                // condition: server is healthy, health checker is not given.
                // response: SERVICE_UNKNOWN
                Arguments.of(new GrpcHealthCheckService(
                                     ImmutableSet.of(),
                                     ImmutableMap.of(),
                                     ImmutableList.of()),
                             "com.linecorp.armeria.server.grpc.TestService",
                             true,
                             ServingStatus.SERVICE_UNKNOWN),
                // request: com.linecorp.armeria.server.grpc.TestService (not registered)
                // condition: server is unhealthy, health checker is not given.
                // response: SERVICE_UNKNOWN
                Arguments.of(new GrpcHealthCheckService(
                                     ImmutableSet.of(),
                                     ImmutableMap.of(),
                                     ImmutableList.of()),
                             "com.linecorp.armeria.server.grpc.TestService",
                             false,
                             ServingStatus.NOT_SERVING),
                // request: com.linecorp.armeria.server.grpc.TestService (registered)
                // condition:
                //    - server is healthy,
                //    - health checker is not given.
                //    - grpc service health checker is specified
                // response: SERVING
                Arguments.of(new GrpcHealthCheckService(
                                     ImmutableSet.of(),
                                     ImmutableMap.of(
                                             "com.linecorp.armeria.server.grpc.TestService",
                                             new SettableHealthChecker(true)
                                     ),
                                     ImmutableList.of()),
                             "com.linecorp.armeria.server.grpc.TestService",
                             true,
                             ServingStatus.SERVING),
                // request: com.linecorp.armeria.server.grpc.TestService (registered)
                // condition:
                //    - server is unhealthy,
                //    - health checker is not given.
                //    - grpc service health checker is specified
                // response: NOT_SERVING
                Arguments.of(new GrpcHealthCheckService(
                                     ImmutableSet.of(),
                                     ImmutableMap.of(
                                             "com.linecorp.armeria.server.grpc.TestService",
                                             new SettableHealthChecker(true)
                                     ),
                                     ImmutableList.of()),
                             "com.linecorp.armeria.server.grpc.TestService",
                             false,
                             ServingStatus.NOT_SERVING),
                // request: empty server name
                // condition:
                //    - server is healthy,
                //    - health checker is not given.
                //    - grpc service health checker is specified
                // response: SERVING
                Arguments.of(new GrpcHealthCheckService(
                                     ImmutableSet.of(),
                                     ImmutableMap.of(
                                             "com.linecorp.armeria.server.grpc.TestService",
                                             new SettableHealthChecker(true)
                                     ),
                                     ImmutableList.of()),
                             "",
                             true,
                             ServingStatus.SERVING),
                // request: empty server name
                // condition:
                //    - server is healthy,
                //    - health checker is not given.
                //    - grpc service health checker (unhealthy) is specified
                // response: NOT_SERVING
                Arguments.of(new GrpcHealthCheckService(
                                     ImmutableSet.of(),
                                     ImmutableMap.of(
                                             "com.linecorp.armeria.server.grpc.TestService",
                                             new SettableHealthChecker(false)
                                     ),
                                     ImmutableList.of()),
                             "",
                             true,
                             ServingStatus.NOT_SERVING),
                // request: empty server name
                // condition:
                //    - server is healthy,
                //    - health checker is set.
                //    - grpc service health checker is specified
                // response: SERVING
                Arguments.of(new GrpcHealthCheckService(
                                     ImmutableSet.of(
                                             new SettableHealthChecker(true)
                                     ),
                                     ImmutableMap.of(
                                             "com.linecorp.armeria.server.grpc.TestService",
                                             new SettableHealthChecker(true)
                                     ),
                                     ImmutableList.of()),
                             "",
                             true,
                             ServingStatus.SERVING),
                // request: empty server name
                // condition:
                //    - server is healthy,
                //    - health checker (unhealthy) is set.
                //    - grpc service health checker is specified
                // response: NOT_SERVING
                Arguments.of(new GrpcHealthCheckService(
                                     ImmutableSet.of(
                                             new SettableHealthChecker(false)
                                     ),
                                     ImmutableMap.of(
                                             "com.linecorp.armeria.server.grpc.TestService",
                                             new SettableHealthChecker(true)
                                     ),
                                     ImmutableList.of()),
                             "",
                             true,
                             ServingStatus.NOT_SERVING),
                // request: empty server name
                // condition:
                //    - server is healthy,
                //    - health checkers are set.
                //    - grpc service health checkers are specified
                // response: SERVING
                Arguments.of(new GrpcHealthCheckService(
                                     ImmutableSet.of(
                                             new SettableHealthChecker(true),
                                             new SettableHealthChecker(true),
                                             new SettableHealthChecker(true)
                                     ),
                                     ImmutableMap.of(
                                             "com.linecorp.armeria.server.grpc.TestService1",
                                             new SettableHealthChecker(true),
                                             "com.linecorp.armeria.server.grpc.TestService2",
                                             new SettableHealthChecker(true)
                                     ),
                                     ImmutableList.of()),
                             "",
                             true,
                             ServingStatus.SERVING),
                // request: empty server name
                // condition:
                //    - server is healthy,
                //    - health checkers is set.
                //    - grpc service health checkers (include unhealthy service) are specified
                // response: NOT_SERVING
                Arguments.of(new GrpcHealthCheckService(
                                     ImmutableSet.of(
                                             new SettableHealthChecker(true),
                                             new SettableHealthChecker(true),
                                             new SettableHealthChecker(true)
                                     ),
                                     ImmutableMap.of(
                                             "com.linecorp.armeria.server.grpc.TestService1",
                                             new SettableHealthChecker(true),
                                             "com.linecorp.armeria.server.grpc.TestService2",
                                             new SettableHealthChecker(false)
                                     ),
                                     ImmutableList.of()),
                             "",
                             true,
                             ServingStatus.NOT_SERVING)
        );
    }

    private static class TestServiceImpl extends TestServiceImplBase {

        @Override
        public void unaryCall(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            responseObserver.onNext(SimpleResponse.newBuilder()
                                                  .setUsername("test user")
                                                  .build());
            responseObserver.onCompleted();
        }
    }
}
