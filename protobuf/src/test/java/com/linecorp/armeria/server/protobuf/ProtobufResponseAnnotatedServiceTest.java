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

package com.linecorp.armeria.server.protobuf;

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.reactivestreams.Publisher;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.MediaTypeNames;
import com.linecorp.armeria.protobuf.testing.Messages.SimpleResponse;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.ExceptionHandler;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Produces;
import com.linecorp.armeria.server.annotation.ProducesJson;
import com.linecorp.armeria.server.annotation.ProducesJsonSequences;
import com.linecorp.armeria.server.annotation.ProducesProtobuf;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import reactor.core.publisher.Flux;

class ProtobufResponseAnnotatedServiceTest {

    private static final byte RECORD_SEPARATOR = 0x1E;
    private static final byte LINE_FEED = 0x0A;

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
            return SimpleResponse.newBuilder().setMessage("Hello, Armeria!").build();
        }

        @Get("/protobuf")
        @ProducesProtobuf
        public SimpleResponse produceProtobuf() {
            return SimpleResponse.newBuilder().setMessage("Hello, Armeria!").build();
        }

        @Get("/json")
        @ProducesJson
        public SimpleResponse produceJson() {
            return SimpleResponse.newBuilder().setMessage("Hello, Armeria!").build();
        }

        @Get("/protobuf+json")
        @Produces("application/protobuf+json")
        public SimpleResponse protobufJson() {
            return SimpleResponse.newBuilder().setMessage("Hello, Armeria!").build();
        }

        @Get("/protobuf+json/publisher")
        @ProducesJson
        public Publisher<SimpleResponse> protobufJsonPublisher() {
            return Flux.just(SimpleResponse.newBuilder().setMessage("Hello, Armeria1!").build(),
                             SimpleResponse.newBuilder().setMessage("Hello, Armeria2!").build());
        }

        @Get("/protobuf+json/stream")
        @Produces("application/protobuf+json")
        public Stream<SimpleResponse> protobufJsonStream() {
            return Stream.of(SimpleResponse.newBuilder().setMessage("Hello, Armeria1!").build(),
                             SimpleResponse.newBuilder().setMessage("Hello, Armeria2!").build());
        }

        @Get("/protobuf+json-seq/publisher")
        @ProducesJsonSequences
        public Publisher<SimpleResponse> protobufJsonSeqPublisher() {
            return Flux.just(SimpleResponse.newBuilder().setMessage("Hello, Armeria1!").build(),
                             SimpleResponse.newBuilder().setMessage("Hello, Armeria2!").build());
        }

        @Get("/protobuf+json-seq/stream")
        @ProducesJsonSequences
        public Stream<SimpleResponse> protobufJsonSeqStream() {
            return Stream.of(SimpleResponse.newBuilder().setMessage("Hello, Armeria1!").build(),
                             SimpleResponse.newBuilder().setMessage("Hello, Armeria2!").build());
        }

        @Get("/protobuf+json/list")
        @Produces("application/protobuf+json")
        public List<SimpleResponse> protobufJsonList() {
            return ImmutableList.of(SimpleResponse.newBuilder().setMessage("Hello, Armeria1!").build(),
                                    SimpleResponse.newBuilder().setMessage("Hello, Armeria2!").build());
        }

        @Get("/protobuf+json/set")
        @Produces("application/protobuf+json")
        public Set<SimpleResponse> protobufJsonSet() {
            return ImmutableSet.of(SimpleResponse.newBuilder().setMessage("Hello, Armeria1!").build(),
                                   SimpleResponse.newBuilder().setMessage("Hello, Armeria2!").build());
        }

        @Get("/protobuf+json/map")
        @Produces("application/protobuf+json")
        public Map<String, SimpleResponse> protobufJsonMap() {
            return ImmutableMap.of("json1", SimpleResponse.newBuilder().setMessage("Hello, Armeria1!").build(),
                                   "json2", SimpleResponse.newBuilder().setMessage("Hello, Armeria2!").build());
        }

        @Get("/protobuf/stream")
        @Produces(MediaTypeNames.PROTOBUF)
        public Stream<SimpleResponse> protobufStream() {
            return Stream.of(SimpleResponse.newBuilder().setMessage("Hello, Armeria1!").build(),
                             SimpleResponse.newBuilder().setMessage("Hello, Armeria2!").build());
        }

        @Get("/protobuf/publisher")
        @Produces(MediaTypeNames.PROTOBUF)
        public Publisher<SimpleResponse> protobufPublisher() {
            return Flux.just(SimpleResponse.newBuilder().setMessage("Hello, Armeria1!").build(),
                             SimpleResponse.newBuilder().setMessage("Hello, Armeria2!").build());
        }
    }

    @CsvSource({ "default-content-type", "protobuf" })
    @ParameterizedTest
    void protobufResponse(String path) throws InvalidProtocolBufferException {
        final AggregatedHttpResponse response = client.get('/' + path).aggregate().join();
        assertThat(response.headers().contentType()).isEqualTo(MediaType.PROTOBUF);
        final SimpleResponse simpleResponse = SimpleResponse.parseFrom(response.content().array());
        assertThat(simpleResponse.getMessage()).isEqualTo("Hello, Armeria!");
    }

    @CsvSource({ "json", "protobuf+json" })
    @ParameterizedTest
    void protobufJsonResponse(String contentType) throws InvalidProtocolBufferException {
        final AggregatedHttpResponse response = client.get('/' + contentType).aggregate().join();
        assertThat(response.headers().contentType().subtype()).isEqualTo(contentType);
        final SimpleResponse.Builder simpleResponse = SimpleResponse.newBuilder();
        JsonFormat.parser().merge(response.contentUtf8(), simpleResponse);
        assertThat(simpleResponse.getMessage()).isEqualTo("Hello, Armeria!");
    }

    @CsvSource({ "/protobuf/stream", "/protobuf/publisher" })
    @ParameterizedTest
    void protobufStreamResponse(String path) {
        final AggregatedHttpResponse response = client.get(path).aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(cause).isInstanceOf(IllegalArgumentException.class)
                         .hasMessageContaining("Cannot convert a");
    }

    @Test
    void protobufJsonPublisherResponse() {
        final AggregatedHttpResponse response = client.get("/protobuf+json/publisher").aggregate().join();
        final MediaType mediaType = response.headers().contentType();
        assertThat(mediaType.is(MediaType.JSON)).isTrue();
        final String expected = "[{\"message\":\"Hello, Armeria1!\"},{\"message\":\"Hello, Armeria2!\"}]";
        assertThatJson(response.contentUtf8()).isEqualTo(expected);
    }

    @CsvSource({ "/protobuf+json-seq/stream", "/protobuf+json-seq/publisher" })
    @ParameterizedTest
    void protobufJsonSeqResponse(String path) throws IOException {
        final AggregatedHttpResponse response = client.get(path).aggregate().join();
        final MediaType mediaType = response.headers().contentType();
        assertThat(mediaType.is(MediaType.JSON_SEQ)).isTrue();

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(RECORD_SEPARATOR);
        out.write("{\"message\":\"Hello, Armeria1!\"}".getBytes());
        out.write(LINE_FEED);
        out.write(RECORD_SEPARATOR);
        out.write("{\"message\":\"Hello, Armeria2!\"}".getBytes());
        out.write(LINE_FEED);

        assertThatJson(response.content().array()).isEqualTo(out.toByteArray());
    }

    @CsvSource({"stream", "list", "set"})
    @ParameterizedTest
    void protobufJsonCollectionResponse(String type) {
        final AggregatedHttpResponse response = client.get("/protobuf+json/" + type).aggregate().join();
        final MediaType mediaType = response.headers().contentType();
        assertThat(mediaType.subtype()).isEqualTo("protobuf+json");
        final String expected = "[{\"message\":\"Hello, Armeria1!\"},{\"message\":\"Hello, Armeria2!\"}]";
        assertThatJson(response.contentUtf8()).isEqualTo(expected);
    }

    @Test
    void protobufJsonMapResponse() throws InvalidProtocolBufferException {
        final AggregatedHttpResponse response = client.get("/protobuf+json/map").aggregate().join();
        final MediaType mediaType = response.headers().contentType();
        assertThat(mediaType.subtype()).isEqualTo("protobuf+json");
        final String expected = "{\"json1\": {\"message\":\"Hello, Armeria1!\"}," +
                                "\"json2\": {\"message\":\"Hello, Armeria2!\"}}";
        assertThatJson(response.contentUtf8()).isEqualTo(expected);
    }

    private static class CustomExceptionHandlerFunction implements ExceptionHandlerFunction {
        @Override
        public HttpResponse handleException(ServiceRequestContext ctx, HttpRequest req, Throwable cause) {
            ProtobufResponseAnnotatedServiceTest.cause = cause;
            return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
