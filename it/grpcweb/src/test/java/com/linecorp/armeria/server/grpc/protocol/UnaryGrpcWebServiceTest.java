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
package com.linecorp.armeria.server.grpc.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;

import com.example.helloworld.GreeterServiceHandlerFactory;
import com.example.helloworld.GreeterServiceImpl;
import com.example.helloworld.HelloReply;
import com.example.helloworld.HelloRequest;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.grpc.protocol.UnaryGrpcClient;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.internal.common.grpc.protocol.UnaryGrpcSerializationFormats;

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

class UnaryGrpcWebServiceTest {

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

    @ParameterizedTest
    @ArgumentsSource(UnaryGrpcProtoWebSerializationFormats.class)
    void grpcProtoWebClient(SerializationFormat serializationFormat) throws Exception {
        final String serverUri = String.format("http://127.0.0.1:%d", serverBinding.localAddress().getPort());
        final UnaryGrpcClient client = new UnaryGrpcClient(WebClient.of(serverUri), serializationFormat);
        final HelloRequest request = HelloRequest.newBuilder().setName("Armeria").build();
        final byte[] responseBytes = client.execute("/GreeterService/SayHello",
                                                    request.toByteArray()).join();
        final HelloReply response = HelloReply.parseFrom(responseBytes);
        assertThat(response.getMessage()).isEqualTo("Hello, Armeria");
    }

    private static class UnaryGrpcProtoWebSerializationFormats implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(final ExtensionContext context) throws Exception {
            return UnaryGrpcSerializationFormats.values().stream()
                                                .filter(UnaryGrpcSerializationFormats::isGrpcWeb)
                                                .map(Arguments::of);
        }
    }
}
