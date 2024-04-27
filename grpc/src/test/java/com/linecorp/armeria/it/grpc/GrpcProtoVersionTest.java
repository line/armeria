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

package com.linecorp.armeria.it.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.common.grpc.GrpcJsonMarshaller;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.stub.StreamObserver;
import testing.grpc.Proto2.Foo2;
import testing.grpc.Proto2.Proto2Message;
import testing.grpc.Proto2ServiceGrpc.Proto2ServiceBlockingStub;
import testing.grpc.Proto2ServiceGrpc.Proto2ServiceImplBase;
import testing.grpc.Proto3.Foo3;
import testing.grpc.Proto3.Proto3Message;
import testing.grpc.Proto3ServiceGrpc.Proto3ServiceBlockingStub;
import testing.grpc.Proto3ServiceGrpc.Proto3ServiceImplBase;
import testing.grpc.Proto3WithProto2ServiceGrpc.Proto3WithProto2ServiceBlockingStub;
import testing.grpc.Proto3WithProto2ServiceGrpc.Proto3WithProto2ServiceImplBase;

class GrpcProtoVersionTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final GrpcService proto2Service =
                    GrpcService.builder().addService(new Proto2ServiceImplBase() {
                        @Override
                        public void echo(Proto2Message request,
                                         StreamObserver<Proto2Message> responseObserver) {
                            responseObserver.onNext(request);
                            responseObserver.onCompleted();
                        }
                    }).jsonMarshallerFactory(sd -> GrpcJsonMarshaller.ofGson()).build();
            final GrpcService proto3Service =
                    GrpcService.builder().addService(new Proto3ServiceImplBase() {
                        @Override
                        public void echo(Proto3Message request,
                                         StreamObserver<Proto3Message> responseObserver) {
                            responseObserver.onNext(request);
                            responseObserver.onCompleted();
                        }
                    }).build();
            final GrpcService proto3WithProto2Service =
                    GrpcService.builder().addService(new Proto3WithProto2ServiceImplBase() {
                        @Override
                        public void echo(Proto2Message request,
                                         StreamObserver<Proto2Message> responseObserver) {
                            responseObserver.onNext(request);
                            responseObserver.onCompleted();
                        }
                    }).jsonMarshallerFactory(sd -> GrpcJsonMarshaller.ofGson()).build();
            sb.idleTimeout(Duration.ZERO)
              .requestTimeout(Duration.ZERO)
              .service(proto2Service)
              .service(proto3Service)
              .service(proto3WithProto2Service);
        }
    };

    @Test
    void testProto3() {
        final Proto3ServiceBlockingStub proto3Service =
                GrpcClients.builder(server.httpUri())
                           .serializationFormat(GrpcSerializationFormats.JSON)
                           .writeTimeout(Duration.ZERO)
                           .responseTimeout(Duration.ZERO)
                           .build(Proto3ServiceBlockingStub.class);
        final Proto3Message message = Proto3Message.newBuilder().setFoo(Foo3.B3).build();
        assertThat(proto3Service.echo(message)).isEqualTo(message);
    }

    @Test
    void testProto2() {
        final Proto2ServiceBlockingStub proto2Service =
                GrpcClients.builder(server.httpUri(GrpcSerializationFormats.JSON))
                           .jsonMarshallerFactory(sd -> GrpcJsonMarshaller.ofGson())
                           .writeTimeout(Duration.ZERO)
                           .responseTimeout(Duration.ZERO)
                           .build(Proto2ServiceBlockingStub.class);
        final Proto2Message message = Proto2Message.newBuilder().setFoo(Foo2.B2).build();
        assertThat(proto2Service.echo(message)).isEqualTo(message);
    }

    @Test
    void testProto3WithProto2() {
        final Proto3WithProto2ServiceBlockingStub proto2Service =
                GrpcClients.builder(server.httpUri(GrpcSerializationFormats.JSON))
                           .jsonMarshallerFactory(sd -> GrpcJsonMarshaller.ofGson())
                           .writeTimeout(Duration.ZERO)
                           .responseTimeout(Duration.ZERO)
                           .build(Proto3WithProto2ServiceBlockingStub.class);
        final Proto2Message message = Proto2Message.newBuilder().setFoo(Foo2.B2).build();
        assertThat(proto2Service.echo(message)).isEqualTo(message);
    }
}
