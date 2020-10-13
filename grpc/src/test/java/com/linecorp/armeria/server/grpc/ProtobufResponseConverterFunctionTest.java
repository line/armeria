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

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.reactivestreams.Publisher;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.MediaTypeNames;
import com.linecorp.armeria.grpc.testing.Messages.SimpleResponse;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.ExceptionHandler;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Produces;
import com.linecorp.armeria.server.annotation.ProducesJson;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import reactor.core.publisher.Flux;

class ProtobufResponseConverterFunctionTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.annotatedService(new GreetingService());
        }
    };

    @Nullable
    static Throwable cause;

    WebClient client;

    @BeforeEach
    void setUp() {
        client = WebClient.of(server.httpUri());
    }

    @AfterEach
    void tearDown() {
        cause = null;
    }

    @ExceptionHandler(CustomExceptionHandlerFunction.class)
    private static class GreetingService {
        @Get("/default-content-type")
        public SimpleResponse noContentType() {
            return SimpleResponse.newBuilder().setUsername("Hello, Armeria!").build();
        }

        @Get("/json")
        @ProducesJson
        public SimpleResponse produceJson() {
            return SimpleResponse.newBuilder().setUsername("Hello, Armeria!").build();
        }

        @Get("/protobuf+json")
        @Produces("application/protobuf+json")
        public SimpleResponse protobufJson() {
            return SimpleResponse.newBuilder().setUsername("Hello, Armeria!").build();
        }

        @Get("/protobuf+json/publisher")
        @Produces("application/protobuf+json")
        public Publisher<SimpleResponse> protobufJsonPublisher() {
            return Flux.just(SimpleResponse.newBuilder().setUsername("Hello, Armeria1!").build(),
                             SimpleResponse.newBuilder().setUsername("Hello, Armeria2!").build());
        }

        @Get("/protobuf+json/stream")
        @Produces("application/protobuf+json")
        public Stream<SimpleResponse> protobufJsonStream() {
            return Stream.of(SimpleResponse.newBuilder().setUsername("Hello, Armeria1!").build(),
                             SimpleResponse.newBuilder().setUsername("Hello, Armeria2!").build());
        }

        @Get("/protobuf/stream")
        @Produces(MediaTypeNames.PROTOBUF)
        public Stream<SimpleResponse> protobufStream() {
            return Stream.of(SimpleResponse.newBuilder().setUsername("Hello, Armeria1!").build(),
                             SimpleResponse.newBuilder().setUsername("Hello, Armeria2!").build());
        }

        @Get("/protobuf/publisher")
        @Produces(MediaTypeNames.PROTOBUF)
        public Publisher<SimpleResponse> protobufPublisher() {
            return Flux.just(SimpleResponse.newBuilder().setUsername("Hello, Armeria1!").build(),
                             SimpleResponse.newBuilder().setUsername("Hello, Armeria2!").build());
        }
    }

    @Test
    void protobufResponse() throws InvalidProtocolBufferException {
        final AggregatedHttpResponse response = client.get("/default-content-type").aggregate().join();
        assertThat(response.headers().contentType()).isEqualTo(MediaType.PROTOBUF);
        final SimpleResponse simpleResponse = SimpleResponse.parseFrom(response.content().array());
        assertThat(simpleResponse.getUsername()).isEqualTo("Hello, Armeria!");
    }

    @CsvSource({"json", "protobuf+json"})
    @ParameterizedTest
    void protobufJsonResponse(String contentType) throws InvalidProtocolBufferException {
        final AggregatedHttpResponse response = client.get('/' + contentType).aggregate().join();
        assertThat(response.headers().contentType().subtype()).isEqualTo(contentType);
        final SimpleResponse.Builder simpleResponse = SimpleResponse.newBuilder();
        JsonFormat.parser().merge(response.contentUtf8(), simpleResponse);
        assertThat(simpleResponse.getUsername()).isEqualTo("Hello, Armeria!");
    }

    @CsvSource({ "/protobuf/stream", "/protobuf/publisher" })
    @ParameterizedTest
    void protobufStreamResponse(String path) throws IOException {
        final AggregatedHttpResponse response = client.get(path).aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(cause).isInstanceOf(IllegalStateException.class)
                         .hasMessageContaining("(expected: a subtype of com.google.protobuf.MessageLite");
    }

    @CsvSource({ "/protobuf+json/publisher", "/protobuf+json/stream"})
    @ParameterizedTest
    void protobufJsonStreamResponse(String path) throws InvalidProtocolBufferException {
        final AggregatedHttpResponse response = client.get(path).aggregate().join();
        assertThat(response.headers().contentType().subtype()).isEqualTo("protobuf+json");
        final String expected = "[{\"username\":\"Hello, Armeria1!\"},{\"username\":\"Hello, Armeria2!\"}]";
        assertThatJson(response.contentUtf8()).isEqualTo(expected);
    }

    private static class CustomExceptionHandlerFunction implements ExceptionHandlerFunction {
        @Override
        public HttpResponse handleException(ServiceRequestContext ctx, HttpRequest req, Throwable cause) {
            ProtobufResponseConverterFunctionTest.cause = cause;
            return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
