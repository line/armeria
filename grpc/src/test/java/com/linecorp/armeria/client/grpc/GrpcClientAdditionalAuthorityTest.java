/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.client.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.testing.MockAddressResolverGroup;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.MethodDescriptor;
import io.grpc.stub.StreamObserver;
import testing.grpc.Messages.SimpleRequest;
import testing.grpc.Messages.SimpleResponse;
import testing.grpc.TestServiceGrpc;
import testing.grpc.TestServiceGrpc.TestServiceBlockingStub;

class GrpcClientAdditionalAuthorityTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.decorator(LoggingService.newDecorator());
            sb.virtualHost("foo.com")
              .service(GrpcService.builder()
                                  .addService(new FooService())
                                  .build())
              .and()
              .virtualHost("bar.com")
              .service(GrpcService.builder()
                                  .addService(new BarService())
                                  .build())
              .and()
              .virtualHost("baz.com")
              .service(GrpcService.builder()
                                  .addService(new BazService())
                                  .build());
        }
    };

    private static ClientFactory clientFactory;

    @BeforeAll
    static void beforeAll() {
        clientFactory = ClientFactory.builder()
                                     .addressResolverGroupFactory(
                                             eventLoop -> MockAddressResolverGroup.localhost())
                                     .build();
    }

    @AfterAll
    static void afterAll() {
        clientFactory.closeAsync();
    }

    @CsvSource({ "h1c, :authority", "h2c, :authority", "http, :authority",
                 "h1c, Host", "h2c, Host", "http, Host" })
    @ParameterizedTest
    void shouldRespectAuthorityInAdditionalHeaders(String protocol, String headerName) {
        final TestServiceBlockingStub client = GrpcClients.builder(protocol + "://foo.com:" + server.httpPort())
                                                          .factory(clientFactory)
                                                          .build(TestServiceBlockingStub.class);

        try (SafeCloseable ignored = Clients.withHeader(headerName, "bar.com");
             ClientRequestContextCaptor captor = Clients.newContextCaptor()) {

            final SimpleResponse response = client.unaryCall(SimpleRequest.getDefaultInstance());
            assertThat(response.getUsername()).isEqualTo("BarService");
            assertThat(captor.get().endpoint().authority())
                    .isEqualTo("foo.com:" + server.httpPort());
        }
    }

    @CsvSource({ "h1c, :authority", "h2c, :authority", "http, :authority",
                 "h1c, Host", "h2c, Host", "http, Host" })
    @ParameterizedTest
    void shouldRespectAuthorityInDefaultHeaders(String protocol, String headerName) {
        final TestServiceBlockingStub client = GrpcClients.builder(protocol + "://foo.com:" + server.httpPort())
                                                          .setHeader(headerName, "bar.com")
                                                          .factory(clientFactory)
                                                          .build(TestServiceBlockingStub.class);

        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final SimpleResponse response = client.unaryCall(SimpleRequest.getDefaultInstance());
            assertThat(response.getUsername()).isEqualTo("BarService");
            assertThat(captor.get().endpoint().authority())
                    .isEqualTo("foo.com:" + server.httpPort());
        }
    }

    @CsvSource({ "h1c, :authority", "h2c, :authority", "http, :authority",
                 "h1c, Host", "h2c, Host", "http, Host" })
    @ParameterizedTest
    void shouldRespectAuthorityInCallAuthority(String protocol, String headerName) {
        final ClientInterceptor authorityOverridingInterceptor = new ClientInterceptor() {
            @Override
            public <I, O> ClientCall<I, O> interceptCall(
                    MethodDescriptor<I, O> method,
                    CallOptions callOptions, Channel next) {
                return next.newCall(method, callOptions.withAuthority("bar.com"));
            }
        };

        final TestServiceBlockingStub client = GrpcClients.builder(protocol + "://foo.com:" + server.httpPort())
                                                          .factory(clientFactory)
                                                          .setHeader(headerName, "baz.com")
                                                          .intercept(authorityOverridingInterceptor)
                                                          .build(TestServiceBlockingStub.class);

        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final SimpleResponse response = client.unaryCall(SimpleRequest.getDefaultInstance());
            assertThat(response.getUsername()).isEqualTo("BarService");
            assertThat(captor.get().endpoint().authority())
                    .isEqualTo("foo.com:" + server.httpPort());
        }
    }

    @CsvSource({ "h1c", "h2c", "http", })
    @ParameterizedTest
    void shouldUseEndpointAsAuthority(String protocol) {
        final TestServiceBlockingStub client = GrpcClients.builder(protocol + "://foo.com:" + server.httpPort())
                                                          .factory(clientFactory)
                                                          .build(TestServiceBlockingStub.class);

        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final SimpleResponse response = client.unaryCall(SimpleRequest.getDefaultInstance());
            assertThat(response.getUsername()).isEqualTo("FooService");
            assertThat(captor.get().endpoint().authority())
                    .isEqualTo("foo.com:" + server.httpPort());
        }
    }

    @CsvSource({ "h1c, :authority", "h2c, :authority", "http, :authority",
                 "h1c, Host", "h2c, Host", "http, Host" })
    @ParameterizedTest
    void shouldOverrideCallAuthorityWithAdditionalAuthority(String protocol, String headerName) {
        final ClientInterceptor authorityOverridingInterceptor = new ClientInterceptor() {
            @Override
            public <I, O> ClientCall<I, O> interceptCall(
                    MethodDescriptor<I, O> method,
                    CallOptions callOptions, Channel next) {
                return next.newCall(method, callOptions.withAuthority("ignored.com"));
            }
        };

        final TestServiceBlockingStub client = GrpcClients.builder(protocol + "://foo.com:" + server.httpPort())
                                                          .factory(clientFactory)
                                                          .intercept(authorityOverridingInterceptor)
                                                          .build(TestServiceBlockingStub.class);

        try (SafeCloseable ignored = Clients.withContextCustomizer(
                ctx -> ctx.addAdditionalRequestHeader(headerName, "bar.com"));
             ClientRequestContextCaptor captor = Clients.newContextCaptor()) {

            final SimpleResponse response = client.unaryCall(SimpleRequest.getDefaultInstance());
            assertThat(response.getUsername()).isEqualTo("BarService");
            assertThat(captor.get().endpoint().authority())
                    .isEqualTo("foo.com:" + server.httpPort());
        }
    }

    private static final class FooService extends TestServiceGrpc.TestServiceImplBase {
        @Override
        public void unaryCall(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            responseObserver.onNext(SimpleResponse.newBuilder()
                                                  .setUsername("FooService")
                                                  .build());
            responseObserver.onCompleted();
        }
    }

    private static final class BarService extends TestServiceGrpc.TestServiceImplBase {
        @Override
        public void unaryCall(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            responseObserver.onNext(SimpleResponse.newBuilder()
                                                  .setUsername("BarService")
                                                  .build());
            responseObserver.onCompleted();
        }
    }

    private static final class BazService extends TestServiceGrpc.TestServiceImplBase {
        @Override
        public void unaryCall(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            responseObserver.onNext(SimpleResponse.newBuilder()
                                                  .setUsername("BazService")
                                                  .build());
            responseObserver.onCompleted();
        }
    }
}
