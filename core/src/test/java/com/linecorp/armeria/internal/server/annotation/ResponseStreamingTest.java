/*
 *  Copyright 2022 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.internal.server.annotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.reactivestreams.Publisher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.MediaTypeNames;
import com.linecorp.armeria.common.sse.ServerSentEvent;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.server.annotation.ByteArrayResponseConverterFunction;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.HttpFileResponseConverterFunction;
import com.linecorp.armeria.server.annotation.JacksonResponseConverterFunction;
import com.linecorp.armeria.server.annotation.Produces;
import com.linecorp.armeria.server.annotation.ResponseConverter;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;
import com.linecorp.armeria.server.annotation.ServerSentEventResponseConverterFunction;
import com.linecorp.armeria.server.annotation.StringResponseConverterFunction;
import com.linecorp.armeria.server.file.HttpFile;

import reactor.core.publisher.Mono;

public class ResponseStreamingTest {

    @ArgumentsSource(ConverterProvider.class)
    @ParameterizedTest
    void responseStreaming_converter(ResponseConverterFunction converter, Class<?> serviceClass) {
        for (Method method : serviceClass.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            final Produces annotation = method.getAnnotation(Produces.class);
            final MediaType produceType;
            if (annotation == null) {
                produceType = null;
            } else {
                produceType = MediaType.parse(annotation.value());
            }
            final boolean isResponseStreaming = method.getAnnotation(Streaming.class).value();

            final Type returnType = method.getGenericReturnType();
            assertThat(converter.isResponseStreaming(returnType, produceType))
                    .as("response streaming from %s and %s should be %s", returnType, produceType,
                        isResponseStreaming)
                    .isEqualTo(isResponseStreaming);
        }
    }

    public static final class ConverterProvider implements ArgumentsProvider {

        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) throws Exception {
            return Stream.of(
                    Arguments.of(new ByteArrayResponseConverterFunction(), BinaryService.class),
                    Arguments.of(new JacksonResponseConverterFunction(), JsonService.class),
                    Arguments.of(new ServerSentEventResponseConverterFunction(), ServerSentEventService.class),
                    Arguments.of(new StringResponseConverterFunction(), StringService.class),
                    Arguments.of(new HttpFileResponseConverterFunction(), HttpFileService.class));
        }
    }

    /**
     * A mock service for testing {@link JacksonResponseConverterFunction}.
     */
    public static final class JsonService {
        @Get("/json")
        @Streaming(false)
        public JsonNode json() {
            return new TextNode("json");
        }

        @Get("/anyObjectWithJson")
        @Streaming(false)
        @Produces(MediaTypeNames.JSON)
        public Object anyObjectWithJson() {
            return "anyObjectWithJson";
        }

        @Get("/jsonFuture")
        @Streaming(false)
        public CompletableFuture<JsonNode> jsonFuture() {
            return UnmodifiableFuture.completedFuture(new TextNode("jsonFuture"));
        }

        @Get("/anyObjectWithJsonFuture")
        @Streaming(false)
        @Produces(MediaTypeNames.JSON)
        public CompletableFuture<Object> anyObjectWithJsonFuture() {
            return UnmodifiableFuture.completedFuture("anyObjectWithJsonFuture");
        }

        @Get("/jsonMono")
        @Streaming(false)
        public Mono<JsonNode> jsonMono() {
            return Mono.just(new TextNode("jsonMono"));
        }

        @Get("/anyObjectWithJsonMono")
        @Streaming(false)
        @Produces(MediaTypeNames.JSON)
        public Mono<Object> anyObjectWithJsonMono() {
            return Mono.just("anyObjectWithJsonMono");
        }

        @Get("/anyObjectWithJsonPublisher")
        @Streaming(true)
        @Produces(MediaTypeNames.JSON_SEQ)
        public Publisher<Object> anyObjectWithJsonPublisher() {
            return StreamMessage.of("anyObjectWithJsonPublisher");
        }

        @Get("/anyObjectWithJsonStream")
        @Streaming(true)
        @Produces(MediaTypeNames.JSON_SEQ)
        public Stream<Object> anyObjectWithJsonStream() {
            return Stream.of("anyObjectWithJsonStream");
        }
    }

    /**
     * A mock service for testing {@link ByteArrayResponseConverterFunction}.
     */
    public static final class BinaryService {
        @Streaming(false)
        @Get("/httpData")
        public HttpData httpData() {
            return HttpData.ofUtf8("httpData");
        }

        @Streaming(false)
        @Get("/bytes")
        public byte[] bytes() {
            return "bytes".getBytes();
        }

        @Streaming(false)
        @Get("/httpDataFuture")
        public CompletableFuture<HttpData> httpDataFuture() {
            return UnmodifiableFuture.completedFuture(HttpData.ofUtf8("httpDataFuture"));
        }

        @Streaming(false)
        @Get("/bytesFuture")
        public CompletableFuture<byte[]> bytesFuture() {
            return UnmodifiableFuture.completedFuture("bytesFuture".getBytes());
        }

        @Streaming(false)
        @Get("/httpDataMono")
        public Mono<HttpData> httpDataMono() {
            return Mono.just(HttpData.ofUtf8("httpDataMono"));
        }

        @Streaming(false)
        @Get("/bytesMono")
        public Mono<byte[]> bytesMono() {
            return Mono.just("bytesMono".getBytes());
        }

        @Streaming(false)
        @Produces(MediaTypeNames.APPLICATION_BINARY)
        @Get("/httpDataApplicationBinary")
        public HttpData httpDataApplicationBinary() {
            return HttpData.ofUtf8("httpDataApplicationBinary");
        }

        @Streaming(false)
        @Produces(MediaTypeNames.OCTET_STREAM)
        @Get("/httpDataOctetStream")
        public HttpData httpDataOctetStream() {
            return HttpData.ofUtf8("httpDataOctetStream");
        }

        @Streaming(false)
        @Produces(MediaTypeNames.APPLICATION_BINARY)
        @Get("/bytesApplicationBinary")
        public byte[] bytesApplicationBinary() {
            return "bytesApplicationBinary".getBytes();
        }

        @Streaming(false)
        @Produces(MediaTypeNames.OCTET_STREAM)
        @Get("/bytesOctetStream")
        public byte[] bytesOctetStream() {
            return "bytesOctetStream".getBytes();
        }

        @Streaming(true)
        @Produces(MediaTypeNames.APPLICATION_BINARY)
        @Get("/httpDataApplicationPublisher")
        public Publisher<HttpData> httpDataApplicationPublisher() {
            return StreamMessage.of(HttpData.ofUtf8("httpDataApplicationPublisher"));
        }

        @Streaming(true)
        @Produces(MediaTypeNames.APPLICATION_BINARY)
        @Get("/bytesApplicationPublisher")
        public Publisher<byte[]> bytesApplicationPublisher() {
            return StreamMessage.of("bytesApplicationPublisher".getBytes());
        }

        @Streaming(true)
        @Produces(MediaTypeNames.OCTET_STREAM)
        @Get("/httpDataOctetPublisher")
        public Publisher<HttpData> httpDataOctetPublisher() {
            return StreamMessage.of(HttpData.ofUtf8("httpDataOctetPublisher"));
        }

        @Streaming(true)
        @Produces(MediaTypeNames.OCTET_STREAM)
        @Get("/bytesOctetPublisher")
        public Publisher<byte[]> bytesOctetPublisher() {
            return StreamMessage.of("bytesOctetPublisher".getBytes());
        }

        @Streaming(true)
        @Produces(MediaTypeNames.APPLICATION_BINARY)
        @Get("/httpDataApplicationStream")
        public Stream<HttpData> httpDataApplicationStream() {
            return Stream.of(HttpData.ofUtf8("httpDataApplicationStream"));
        }

        @Streaming(true)
        @Produces(MediaTypeNames.APPLICATION_BINARY)
        @Get("/bytesApplicationStream")
        public Stream<byte[]> bytesApplicationStream() {
            return Stream.of("bytesApplicationStream".getBytes());
        }

        @Streaming(true)
        @Produces(MediaTypeNames.OCTET_STREAM)
        @Get("/httpDataOctetStreamStream")
        public Stream<HttpData> httpDataOctetStreamStream() {
            return Stream.of(HttpData.ofUtf8("httpDataOctetStreamStream"));
        }

        @Streaming(true)
        @Produces(MediaTypeNames.OCTET_STREAM)
        @Get("/bytesOctetStreamStream")
        public Stream<byte[]> bytesOctetStreamStream() {
            return Stream.of("bytesOctetStreamStream".getBytes());
        }
    }

    /**
     * A mock service for testing {@link ServerSentEventResponseConverterFunction}.
     */
    public static final class ServerSentEventService {
        @Streaming(false)
        @Get("/serverSentEvent")
        @ResponseConverter(ServerSentEventResponseConverterFunction.class)
        public ServerSentEvent serverSentEvent() {
            return ServerSentEvent.ofData("serverSentEvent");
        }

        @Streaming(false)
        @Get("/serverSentEventFuture")
        @ResponseConverter(ServerSentEventResponseConverterFunction.class)
        public CompletableFuture<ServerSentEvent> serverSentEventFuture() {
            return UnmodifiableFuture.completedFuture(ServerSentEvent.ofData("serverSentEventFuture"));
        }

        @Streaming(false)
        @Get("/serverSentEventMono")
        @ResponseConverter(ServerSentEventResponseConverterFunction.class)
        public Mono<ServerSentEvent> serverSentEventMono() {
            return Mono.just(ServerSentEvent.ofData("serverSentEventMono"));
        }

        @Produces(MediaTypeNames.EVENT_STREAM)
        @Streaming(true)
        @Get("/serverSentEventPublisher")
        @ResponseConverter(ServerSentEventResponseConverterFunction.class)
        public Publisher<ServerSentEvent> serverSentEventPublisher() {
            return StreamMessage.of(ServerSentEvent.ofData("serverSentEventPublisher"));
        }

        @ResponseConverter(ServerSentEventResponseConverterFunction.class)
        @Produces(MediaTypeNames.EVENT_STREAM)
        @Streaming(true)
        @Get("/serverSentEventStream")
        public Stream<ServerSentEvent> serverSentEventStream() {
            return Stream.of(ServerSentEvent.ofData("serverSentEventStream"));
        }
    }

    public static final class StringService {

        @Streaming(false)
        @Get("/string")
        public String string() {
            return "string";
        }

        @Streaming(false)
        @Get("/stringFuture")
        public CompletableFuture<String> stringFuture() {
            return UnmodifiableFuture.completedFuture("stringFuture");
        }

        @Streaming(false)
        @Get("/stringMono")
        public Mono<String> stringMono() {
            return Mono.just("stringMono");
        }

        @Produces(MediaTypeNames.PLAIN_TEXT)
        @Streaming(false)
        @Get("/stringTextPublisher")
        public Publisher<String> stringTextPublisher() {
            return StreamMessage.of("stringTextPublisher");
        }
    }

    /**
     * A mock service for testing {@link HttpFileResponseConverterFunction}.
     */
    public static final class HttpFileService {
        @Streaming(true)
        @Get("/file")
        public HttpFile file() {
            return HttpFile.of(HttpData.ofUtf8("file"));
        }

        @Streaming(true)
        @Get("/fileFuture")
        public CompletableFuture<HttpFile> fileFuture() {
            return UnmodifiableFuture.completedFuture(HttpFile.of(HttpData.ofUtf8("fileFuture")));
        }

        @Streaming(true)
        @Get("/fileMono")
        public Mono<HttpFile> fileMono() {
            return Mono.just(HttpFile.of(HttpData.ofUtf8("fileMono")));
        }
    }

    /**
     * Indicates whether to enable response streaming.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    public @interface Streaming {
        boolean value();
    }
}
