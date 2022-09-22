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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.grpc.testing.Messages.SimpleRequest;
import com.linecorp.armeria.grpc.testing.Messages.SimpleResponse;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc.TestServiceBlockingStub;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.MethodDescriptor;
import io.grpc.stub.StreamObserver;

class CustomAuthorityTest {

    @RegisterExtension
    static ServerExtension virtualHostingServer = new ServerExtension() {
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
                                  .build());
        }
    };

    @RegisterExtension
    static ServerExtension simpleServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service(GrpcService.builder()
                                  .addService(new BazService())
                                  .build());
        }
    };

    @CsvSource({ "foo.com, FooService", "bar.com, BarService" })
    @ParameterizedTest
    void overrideAuthorityUsingClientBuilder(String authority, String expectation) throws Exception {
        final TestServiceBlockingStub client = GrpcClients.builder(virtualHostingServer.httpUri())
                                                          .authority(authority)
                                                          .build(TestServiceBlockingStub.class);
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final SimpleResponse response = client.unaryCall(SimpleRequest.getDefaultInstance());
            assertThat(response.getUsername()).isEqualTo(expectation);
            // Make sure that the endpoint is not changed by the additional authority.
            assertThat(captor.get().endpoint().authority())
                    .isEqualTo(virtualHostingServer.httpUri().getAuthority());
        }
        final ServiceRequestContext sctx = virtualHostingServer.requestContextCaptor().take();
        assertThat(sctx.request().headers().authority()).isEqualTo(authority);
    }

    @CsvSource({ "foo.com, FooService", "bar.com, BarService" })
    @ParameterizedTest
    void overrideAuthorityUsingCallOptions(String authority, String expectation) throws Exception {
        final ClientInterceptor authorityOverridingInterceptor = new ClientInterceptor() {
            @Override
            public <I, O> ClientCall<I, O> interceptCall(
                    MethodDescriptor<I, O> method,
                    CallOptions callOptions, Channel next) {
                return next.newCall(method, callOptions.withAuthority(authority));
            }
        };
        final TestServiceBlockingStub client = GrpcClients.builder(virtualHostingServer.httpUri())
                                                          .intercept(authorityOverridingInterceptor)
                                                          .build(TestServiceBlockingStub.class);

        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final SimpleResponse response = client.unaryCall(SimpleRequest.getDefaultInstance());
            assertThat(response.getUsername()).isEqualTo(expectation);
            // Make sure that the endpoint is not changed by the additional authority.
            assertThat(captor.get().endpoint().authority())
                    .isEqualTo(virtualHostingServer.httpUri().getAuthority());
        }
        final ServiceRequestContext sctx = virtualHostingServer.requestContextCaptor().take();
        assertThat(sctx.request().headers().authority()).isEqualTo(authority);
    }

    @Test
    void shouldUseBaseUriAsEndpoint() throws Exception {
        final TestServiceBlockingStub client = GrpcClients.builder(simpleServer.httpUri())
                                                          .authority("foo.com")
                                                          .build(TestServiceBlockingStub.class);
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final SimpleResponse response = client.unaryCall(SimpleRequest.getDefaultInstance());
            assertThat(response.getUsername()).isEqualTo("BazService");
            // Make sure that the endpoint is not changed by the additional authority.
            assertThat(captor.get().endpoint().authority())
                    .isEqualTo(simpleServer.httpUri().getAuthority());
        }
        final ServiceRequestContext sctx = simpleServer.requestContextCaptor().take();
        assertThat(sctx.request().headers().authority()).isEqualTo("foo.com");
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
