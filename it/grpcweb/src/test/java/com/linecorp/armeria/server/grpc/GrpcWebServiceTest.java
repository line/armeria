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
package com.linecorp.armeria.server.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;

import com.example.helloworld.GreeterServiceGrpc.GreeterServiceBlockingStub;
import com.example.helloworld.GreeterServiceHandlerFactory;
import com.example.helloworld.GreeterServiceImpl;
import com.example.helloworld.HelloReply;
import com.example.helloworld.HelloRequest;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.common.logging.RequestLog;

import akka.actor.typed.ActorSystem;
import akka.actor.typed.javadsl.Adapter;
import akka.actor.typed.javadsl.Behaviors;
import akka.grpc.javadsl.WebHandler;
import akka.http.javadsl.ConnectHttp;
import akka.http.javadsl.Http;
import akka.http.javadsl.ServerBinding;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.japi.Function;
import akka.stream.Materializer;
import akka.stream.SystemMaterializer;

class GrpcWebServiceTest {

    private static ServerBinding serverBinding;

    @BeforeAll
    static void setUp() {
        final ActorSystem<Object> system = ActorSystem.create(Behaviors.empty(), "GreeterServer");
        final Materializer materializer = SystemMaterializer.get(system).materializer();
        final Function<HttpRequest, CompletionStage<HttpResponse>> handler =
                GreeterServiceHandlerFactory.create(new GreeterServiceImpl(system), system);
        final Function<HttpRequest, CompletionStage<HttpResponse>> grpcWebServiceHandlers =
                WebHandler.grpcWebHandler(ImmutableList.of(handler), system, materializer);

        final CompletionStage<ServerBinding> future =
                Http.get(Adapter.toClassic(system))
                    .bindAndHandleAsync(grpcWebServiceHandlers, ConnectHttp.toHost("127.0.0.1", 0),
                                        materializer);
        serverBinding = future.toCompletableFuture().join();
    }

    @AfterAll
    static void tearDown() {
        serverBinding.terminate(Duration.ofSeconds(10));
    }

    @ParameterizedTest
    @ArgumentsSource(GrpcProtoWebSerializationFormats.class)
    void grpcProtoWebClient(SerializationFormat serializationFormat) {
        final String serverUri = serializationFormat.uriText() + "+http://127.0.0.1:" +
                                 serverBinding.localAddress().getPort();
        final GreeterServiceBlockingStub blockingStub =
                Clients.newClient(serverUri, GreeterServiceBlockingStub.class);
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final HelloReply armeria =
                    blockingStub.sayHello(HelloRequest.newBuilder().setName("Armeria").build());
            assertThat(armeria.getMessage()).isEqualTo("Hello, Armeria");
            final RequestLog requestLog = captor.get().log().ensureComplete();
            assertThat(requestLog.responseContent().toString()).contains("Hello, Armeria");
        }
    }

    private static class GrpcProtoWebSerializationFormats implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(final ExtensionContext context) throws Exception {
            return GrpcSerializationFormats.values().stream()
                                           .filter(GrpcSerializationFormats::isGrpcWeb)
                                           .filter(GrpcSerializationFormats::isProto)
                                           .map(Arguments::of);
        }
    }
}
