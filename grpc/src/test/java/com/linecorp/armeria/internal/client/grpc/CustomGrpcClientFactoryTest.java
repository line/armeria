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

package com.linecorp.armeria.internal.client.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.grpc.GrpcClientStubFactory;
import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall.SimpleForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServiceDescriptor;
import io.grpc.stub.StreamObserver;
import testing.grpc.Messages.SimpleRequest;
import testing.grpc.Messages.SimpleResponse;
import testing.grpc.TestServiceGrpc;
import testing.grpc.TestServiceGrpc.TestServiceBlockingStub;
import testing.grpc.TestServiceGrpc.TestServiceImplBase;
import testing.grpc.TestServiceGrpc.TestServiceStub;

class CustomGrpcClientFactoryTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension(true) {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.requestTimeoutMillis(5000);
            sb.decorator(LoggingService.newDecorator());
            sb.service(GrpcService.builder()
                                  .addService(new TestServiceImpl())
                                  .build());
        }
    };

    @Test
    void customFactory() {
        final AtomicBoolean invoked = new AtomicBoolean();
        final TestServiceStub client =
                GrpcClients.builder("http://127.0.0.1")
                           .clientStubFactory(new GrpcClientStubFactory() {
                               @Override
                               public ServiceDescriptor findServiceDescriptor(Class<?> clientType) {
                                   invoked.set(true);
                                   return TestServiceGrpc.getServiceDescriptor();
                               }

                               @Override
                               public Object newClientStub(Class<?> clientType, Channel channel) {
                                   return TestServiceGrpc.newStub(channel);
                               }
                           })
                           .build(TestServiceStub.class);

        assertThat(client).isNotNull();
        assertThat(invoked).isTrue();
    }

    @Test
    void illegalType() {
        final AtomicBoolean invoked = new AtomicBoolean();
        assertThatThrownBy(() -> {
            GrpcClients.builder("http://127.0.0.1")
                       .clientStubFactory(new GrpcClientStubFactory() {
                           @Override
                           public ServiceDescriptor findServiceDescriptor(Class<?> clientType) {
                               invoked.set(true);
                               return TestServiceGrpc.getServiceDescriptor();
                           }

                           @Override
                           public Object newClientStub(Class<?> clientType, Channel channel) {
                               return TestServiceGrpc.newBlockingStub(channel);
                           }
                       })
                       .build(TestServiceStub.class);
        }).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Unexpected client stub type: " +
                                TestServiceGrpc.TestServiceBlockingStub.class.getName());
    }

    @Test
    void interceptors() {
        final AtomicInteger invoked1 = new AtomicInteger();
        final AtomicInteger invoked2 = new AtomicInteger();
        final ClientInterceptor firstInterceptor = new ClientInterceptor() {
            @Override
            public <I, O> ClientCall<I, O> interceptCall(
                    MethodDescriptor<I, O> method, CallOptions callOptions, Channel next) {
                return new SimpleForwardingClientCall<I, O>(next.newCall(method, callOptions)) {
                    @Override
                    public void start(Listener<O> responseListener, Metadata headers) {
                        invoked1.getAndIncrement();
                        assertThat(invoked1).hasValue(1);
                        assertThat(invoked2).hasValue(0);
                        super.start(responseListener, headers);
                    }
                };
            }
        };
        final ClientInterceptor secondInterceptor = new ClientInterceptor() {
            @Override
            public <I, O> ClientCall<I, O> interceptCall(
                    MethodDescriptor<I, O> method, CallOptions callOptions, Channel next) {
                return new SimpleForwardingClientCall<I, O>(next.newCall(method, callOptions)) {
                    @Override
                    public void start(Listener<O> responseListener, Metadata headers) {
                        invoked2.getAndIncrement();
                        assertThat(invoked1).hasValue(1);
                        assertThat(invoked2).hasValue(1);
                        super.start(responseListener, headers);
                    }
                };
            }
        };
        final ClientInterceptor thirdInterceptor = new ClientInterceptor() {
            @Override
            public <I, O> ClientCall<I, O> interceptCall(
                    MethodDescriptor<I, O> method, CallOptions callOptions, Channel next) {
                return new SimpleForwardingClientCall<I, O>(next.newCall(method, callOptions)) {
                    @Override
                    public void start(Listener<O> responseListener, Metadata headers) {
                        invoked1.getAndIncrement();
                        assertThat(invoked1).hasValue(2);
                        assertThat(invoked2).hasValue(1);
                        super.start(responseListener, headers);
                    }
                };
            }
        };
        final TestServiceBlockingStub client = GrpcClients.builder(server.httpUri())
                                                          .intercept(thirdInterceptor,
                                                                     secondInterceptor,
                                                                     firstInterceptor)
                                                          .build(TestServiceBlockingStub.class);
        client.unaryCall(SimpleRequest.getDefaultInstance());
        assertThat(invoked1.get()).isEqualTo(2);
        assertThat(invoked2.get()).isEqualTo(1);
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
