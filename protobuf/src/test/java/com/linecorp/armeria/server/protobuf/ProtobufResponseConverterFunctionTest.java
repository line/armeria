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

package com.linecorp.armeria.server.protobuf;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.reactivestreams.Publisher;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.MediaTypeNames;
import com.linecorp.armeria.internal.testing.GenerateNativeImageTrace;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Produces;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import reactor.core.publisher.Mono;
import testing.protobuf.Messages.SimpleResponse;

@GenerateNativeImageTrace
class ProtobufResponseConverterFunctionTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.annotatedService("/ExchangeTypeProtobufService", new ExchangeTypeProtobufService());
            // nested annotated services don't throw an exception
            sb.annotatedService("/NestedProtobufService", new NestedProtobufService());
        }
    };

    @Test
    void responseStreaming_converter() throws NoSuchMethodException {
        final ProtobufResponseConverterFunction converter = new ProtobufResponseConverterFunction();
        for (Method method : ExchangeTypeProtobufService.class.getDeclaredMethods()) {
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
            final String isResponseStreaming = method.getAnnotation(Streaming.class).value();
            final Boolean expected;
            if ("null".equals(isResponseStreaming)) {
                expected = null;
            } else {
                expected = Boolean.valueOf(isResponseStreaming);
            }

            final Type returnType = method.getGenericReturnType();
            assertThat(converter.isResponseStreaming(returnType, produceType))
                    .as("response streaming from %s and %s should be %s", returnType, produceType,
                        isResponseStreaming)
                    .isEqualTo(expected);
        }
    }

    @Test
    void responseStreaming_exchangeType() throws NoSuchMethodException {
        final ProtobufResponseConverterFunction converter = new ProtobufResponseConverterFunction();
        for (Method method : ExchangeTypeProtobufService.class.getDeclaredMethods()) {
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
            final String isResponseStreaming = method.getAnnotation(Streaming.class).value();
            final Boolean expected;
            if ("null".equals(isResponseStreaming)) {
                expected = null;
            } else {
                expected = Boolean.valueOf(isResponseStreaming);
            }

            final Type returnType = method.getGenericReturnType();
            assertThat(converter.isResponseStreaming(returnType, produceType))
                    .as("response streaming from %s and %s should be %s", returnType, produceType,
                        isResponseStreaming)
                    .isEqualTo(expected);
        }
    }

    @Test
    void nestedProtobuf() {
        final ProtobufResponseConverterFunctionProvider provider =
                new ProtobufResponseConverterFunctionProvider();
        for (Method method : NestedProtobufService.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            final ResponseConverterFunction fn = provider.createResponseConverterFunction(
                    method.getGenericReturnType());
            assertThat(fn).isNull();
        }
    }

    private static final class ExchangeTypeProtobufService {
        @Streaming("false")
        @Get("/simple")
        public SimpleResponse simple() {
            return null;
        }

        @Streaming("false")
        @Get("/json")
        @Produces(MediaTypeNames.JSON)
        public SimpleResponse json() {
            return null;
        }

        @Streaming("false")
        @Get("/protobuf")
        @Produces(MediaTypeNames.PROTOBUF)
        public SimpleResponse protobuf() {
            return null;
        }

        @Streaming("false")
        @Get("/simpleFuture")
        public CompletableFuture<SimpleResponse> simpleFuture() {
            return null;
        }

        @Streaming("false")
        @Produces(MediaTypeNames.JSON)
        @Get("/jsonFuture")
        public CompletableFuture<SimpleResponse> jsonFuture() {
            return null;
        }

        @Streaming("false")
        @Produces(MediaTypeNames.PROTOBUF)
        @Get("/protobufFuture")
        public CompletableFuture<SimpleResponse> protobufFuture() {
            return null;
        }

        @Streaming("false")
        @Get("/simpleMono")
        public Mono<SimpleResponse> simpleMono() {
            return null;
        }

        @Streaming("false")
        @Produces(MediaTypeNames.JSON)
        @Get("/jsonMono")
        public Mono<SimpleResponse> jsonMono() {
            return null;
        }

        @Streaming("false")
        @Produces(MediaTypeNames.PROTOBUF)
        @Get("/protobufMono")
        public Mono<SimpleResponse> protobufMono() {
            return null;
        }

        @Streaming("null")
        @Produces(MediaTypeNames.JSON_SEQ)
        @Get("/jsonSeqSimple")
        public List<SimpleResponse> jsonSeqSimple() {
            return null;
        }

        @Streaming("true")
        @Get("/jsonSeqPublisher")
        @Produces(MediaTypeNames.JSON_SEQ)
        public Publisher<SimpleResponse> jsonSeqPublisher() {
            return null;
        }

        @Streaming("true")
        @Get("/jsonSeqStream")
        @Produces(MediaTypeNames.JSON_SEQ)
        public Stream<SimpleResponse> jsonSeqStream() {
            return null;
        }

        @Streaming("null")
        @Get("/unknown")
        public Publisher<SimpleResponse> unknown() {
            return null;
        }
    }

    private static class NestedProtobufService {

        @Get("/nestedList")
        @Produces(MediaTypeNames.JSON)
        public Publisher<List<SimpleResponse>> nestedList() {
            return Mono.just(ImmutableList.of(SimpleResponse.newBuilder()
                                                            .setMessage("nestedList")
                                                            .build()));
        }

        @Get("/doubleNestedList")
        @Produces(MediaTypeNames.JSON)
        public Publisher<List<List<SimpleResponse>>> doubleNestedList() {
            return Mono.just(ImmutableList.of(ImmutableList.of(SimpleResponse.newBuilder()
                                                                             .setMessage("doubleNestedList")
                                                                             .build())));
        }

        @Get("/mapOfList")
        @Produces(MediaTypeNames.JSON)
        public Map<String, List<SimpleResponse>> mapOfList() {
            return ImmutableMap.of("key", ImmutableList.of(SimpleResponse.newBuilder()
                                                                         .setMessage("mapOfList")
                                                                         .build()));
        }
    }

    /**
     * Indicates that response streaming should be enabled.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    public @interface Streaming {
        String value();
    }
}
