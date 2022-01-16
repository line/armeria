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

import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import example.armeria.grpc.Proto2.Foo;
import example.armeria.grpc.Proto2.MessageWithEnum;
import example.armeria.grpc.Proto2ServiceGrpc.Proto2ServiceBlockingStub;
import example.armeria.grpc.Proto2ServiceGrpc.Proto2ServiceImplBase;
import io.grpc.stub.StreamObserver;

class GrpcProto2Test {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final GrpcService service =
                    GrpcService.builder().addService(new Proto2ServiceImplBase() {
                                   @Override
                                   public void simple(MessageWithEnum request,
                                                      StreamObserver<MessageWithEnum> responseObserver) {
                                       responseObserver.onNext(request);
                                       responseObserver.onCompleted();
                                   }
                               }).build();
            sb.idleTimeout(Duration.ZERO)
              .requestTimeout(Duration.ZERO)
              .service(service);
        }
    };

    @Test
    void testProto2EnumForGrpcService() {
        final Proto2ServiceBlockingStub proto2Service =
                Clients.builder(server.httpUri(GrpcSerializationFormats.PROTO))
                       .writeTimeout(Duration.ZERO)
                       .responseTimeout(Duration.ZERO)
                       .build(Proto2ServiceBlockingStub.class);
        final MessageWithEnum message = MessageWithEnum.newBuilder().setFoo(Foo.B).build();
        assertThat(proto2Service.simple(message)).isEqualTo(message);
    }

    @Test
    void testProto2EnumWithJsonMarshalling() {
        final Proto2ServiceBlockingStub proto2Service =
                Clients.builder(server.httpUri(GrpcSerializationFormats.JSON))
                       .writeTimeout(Duration.ZERO)
                       .responseTimeout(Duration.ZERO)
                       .build(Proto2ServiceBlockingStub.class);
        final MessageWithEnum message = MessageWithEnum.newBuilder().setFoo(Foo.B).build();
        assertThat(proto2Service.simple(message)).isEqualTo(message);
    }
}
