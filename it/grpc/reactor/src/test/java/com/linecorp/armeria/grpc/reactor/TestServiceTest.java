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

package com.linecorp.armeria.grpc.reactor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.server.HttpServiceWithRoutes;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.grpc.GrpcService;

import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
import testing.grpc.Hello.HelloReply;
import testing.grpc.Hello.HelloRequest;
import testing.grpc.ReactorTestServiceGrpc;

class TestServiceTest {

    private static Server server;
    private static ReactorTestServiceGrpc.ReactorTestServiceStub service;

    @BeforeAll
    static void beforeClass() throws Exception {
        server = newServer(0);
        server.start().join();
        service = GrpcClients.newClient(uri(), ReactorTestServiceGrpc.ReactorTestServiceStub.class);
    }

    @AfterAll
    static void afterClass() {
        if (server != null) {
            server.stop().join();
        }
    }

    private static Server newServer(int httpPort) {
        final HttpServiceWithRoutes grpcService =
                GrpcService.builder()
                           .addService(new TestServiceImpl())
                           .exceptionMapping((ctx, throwable, metadata) -> {
                               if (throwable instanceof TestServiceImpl.AuthException) {
                                   return Status.UNAUTHENTICATED.withDescription(throwable.getMessage())
                                                                .withCause(throwable);
                               }
                               return null;
                           })
                           .build();
        return Server.builder().http(httpPort).service(grpcService).build();
    }

    private static String uri() {
        return "gproto+http://127.0.0.1:" + server.activeLocalPort() + '/';
    }

    @Test
    void getLotsOfRepliesWithoutScheduler() {
        final List<String> messages =
                service.lotsOfRepliesWithoutScheduler(HelloRequest.newBuilder().setName("Armeria").build())
                       .map(HelloReply::getMessage)
                       .collectList()
                       .block();

        assertThat(messages).hasSize(5);

        for (int i = 0; i < messages.size(); i++) {
            assertThat(messages.get(i)).isEqualTo("Hello, Armeria! (sequence: " + (i + 1) + ')');
        }
    }

    @Test
    void exceptionMapping() {
        assertThatThrownBy(() -> {
            service.helloError(HelloRequest.newBuilder()
                                           .setName("Armeria")
                                           .build())
                   .map(HelloReply::getMessage)
                   .block();
        }).isInstanceOfSatisfying(StatusRuntimeException.class, e -> {
            assertThat(e.getStatus().getCode()).isEqualTo(Code.UNAUTHENTICATED);
            assertThat(e.getMessage()).isEqualTo("UNAUTHENTICATED: Armeria is unauthenticated");
        });
    }
}
