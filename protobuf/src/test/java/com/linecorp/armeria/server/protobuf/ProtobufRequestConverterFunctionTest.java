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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.ParameterizedType;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.reflect.TypeToken;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import com.google.protobuf.util.JsonFormat.Printer;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.protobuf.testing.Messages.SimpleRequest;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.FallthroughException;
import com.linecorp.armeria.server.protobuf.ProtobufRequestConverterFunction.ResultType;

class ProtobufRequestConverterFunctionTest {

    private static final ServiceRequestContext ctx =
            ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));

    private static final SimpleRequest simpleRequest1 =
            SimpleRequest.newBuilder().setPayload("Armeria").build();
    private static final SimpleRequest simpleRequest2 =
            SimpleRequest.newBuilder().setPayload("Protobuf").build();
    private static final Printer printer = JsonFormat.printer();

    @ArgumentsSource(ProtobufArguments.class)
    @ParameterizedTest
    void protobuf(ProtobufRequestConverterFunction converter, AggregatedHttpRequest req) throws Exception {
        final Object requestObject = converter.convertRequest(ctx, req, SimpleRequest.class, null);
        assertThat(requestObject).isEqualTo(simpleRequest1);
    }

    @Test
    void protobufList_fallthrough() throws Exception {
        final AggregatedHttpRequest req = AggregatedHttpRequest.of(ctx.request().headers(),
                                                                   HttpData.wrap(simpleRequest1.toByteArray()));
        final ProtobufRequestConverterFunction converter =
                new ProtobufRequestConverterFunction(ResultType.LIST_PROTOBUF);
        final TypeToken<List<SimpleRequest>> typeToken = new TypeToken<List<SimpleRequest>>() {};
        assertThatThrownBy(() -> {
            converter.convertRequest(ctx, req, typeToken.getRawType(), (ParameterizedType) typeToken.getType());
        }).isInstanceOf(FallthroughException.class);
    }

    @Test
    void protobufList() throws Exception {
        final ProtobufRequestConverterFunction converter =
                new ProtobufRequestConverterFunction(ResultType.LIST_PROTOBUF);
        final TypeToken<List<SimpleRequest>> typeToken = new TypeToken<List<SimpleRequest>>() {};

        final ImmutableList<SimpleRequest> simpleRequests = ImmutableList.of(simpleRequest1, simpleRequest2);
        final AggregatedHttpRequest req =
                AggregatedHttpRequest.of(ctx.request().headers()
                                            .withMutations(
                                                    builder -> builder.contentType(MediaType.JSON)),
                                         HttpData.ofUtf8(toJson(simpleRequests)));
        final Object requestObject = converter.convertRequest(ctx, req, typeToken.getRawType(),
                                                              (ParameterizedType) typeToken.getType());
        assertThat(requestObject).isEqualTo(simpleRequests);
    }

    @Test
    void protobufSet() throws Exception {
        final ProtobufRequestConverterFunction converter =
                new ProtobufRequestConverterFunction();
        final TypeToken<Set<SimpleRequest>> typeToken = new TypeToken<Set<SimpleRequest>>() {};

        final ImmutableList<SimpleRequest> simpleRequests = ImmutableList.of(simpleRequest1, simpleRequest2);
        final AggregatedHttpRequest req =
                AggregatedHttpRequest.of(ctx.request().headers()
                                            .withMutations(
                                                    builder -> builder.contentType(MediaType.JSON)),
                                         HttpData.ofUtf8(toJson(simpleRequests)));
        final Object requestObject = converter.convertRequest(ctx, req, typeToken.getRawType(),
                                                              (ParameterizedType) typeToken.getType());
        assertThat(requestObject).isEqualTo(ImmutableSet.of(simpleRequest1, simpleRequest2));
    }

    @Test
    void protobufMap() throws Exception {
        final ProtobufRequestConverterFunction converter =
                new ProtobufRequestConverterFunction(ResultType.MAP_PROTOBUF);
        final TypeToken<Map<String, SimpleRequest>> typeToken = new TypeToken<Map<String, SimpleRequest>>() {};

        final String json = "{ \"json1\": " + printer.print(simpleRequest1) +
                            ", \"json2\": " + printer.print(simpleRequest2) + '}';
        final AggregatedHttpRequest req =
                AggregatedHttpRequest.of(ctx.request().headers()
                                            .withMutations(
                                                    builder -> builder.contentType(MediaType.JSON)),
                                         HttpData.ofUtf8(json));
        final Object requestObject = converter.convertRequest(ctx, req, typeToken.getRawType(),
                                                              (ParameterizedType) typeToken.getType());
        assertThat(requestObject).isEqualTo(ImmutableMap.of("json1", simpleRequest1, "json2", simpleRequest2));
    }

    @Test
    void protobufMap_wrongKeyType() throws Exception {
        final ProtobufRequestConverterFunction converter =
                new ProtobufRequestConverterFunction();
        final TypeToken<Map<Integer, SimpleRequest>> typeToken =
                new TypeToken<Map<Integer, SimpleRequest>>() {};

        final String json = "{ \"json1\": " + printer.print(simpleRequest1) +
                            ", \"json2\": " + printer.print(simpleRequest2) + '}';
        final AggregatedHttpRequest req =
                AggregatedHttpRequest.of(ctx.request().headers()
                                            .withMutations(
                                                    builder -> builder.contentType(MediaType.JSON)),
                                         HttpData.ofUtf8(json));
        assertThatThrownBy(() -> converter.convertRequest(ctx, req, typeToken.getRawType(),
                                                          (ParameterizedType) typeToken.getType()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be used for the key type of Map.");
    }

    private static class ProtobufArguments implements ArgumentsProvider {

        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) throws Exception {
            final ImmutableList<ProtobufRequestConverterFunction> converters =
                    ImmutableList.of(new ProtobufRequestConverterFunction(),
                                     new ProtobufRequestConverterFunction(ResultType.PROTOBUF));
            final Stream.Builder<Arguments> builder = Stream.builder();

            for (ProtobufRequestConverterFunction converter : converters) {
                for (AggregatedHttpRequest req : generateRequests(simpleRequest1)) {
                    builder.add(Arguments.of(converter, req));
                }
            }
            return builder.build();
        }

        private static List<AggregatedHttpRequest> generateRequests(SimpleRequest simpleRequest)
                throws InvalidProtocolBufferException {
            return ImmutableList.of(
                    AggregatedHttpRequest.of(ctx.request().headers(),
                                             HttpData.wrap(simpleRequest.toByteArray())),
                    AggregatedHttpRequest.of(ctx.request().headers()
                                                .withMutations(
                                                        builder -> builder.contentType(MediaType.PROTOBUF)),
                                             HttpData.wrap(simpleRequest.toByteArray())),
                    AggregatedHttpRequest.of(ctx.request().headers()
                                                .withMutations(builder -> builder.contentType(MediaType.JSON)),
                                             HttpData.ofUtf8(printer.print(simpleRequest))));
        }
    }

    private static String toJson(List<SimpleRequest> simpleRequests) {
        return simpleRequests.stream()
                             .map(request -> {
                                 try {
                                     return printer.print(request);
                                 } catch (InvalidProtocolBufferException e) {
                                     return Exceptions.throwUnsafely(e);
                                 }
                             })
                             .collect(Collectors.joining(",", "[", "]"));
    }
}
