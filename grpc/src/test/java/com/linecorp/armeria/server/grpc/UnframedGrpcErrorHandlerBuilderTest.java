/*
 * Copyright 2024 LINE Corporation
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

import static com.linecorp.armeria.server.grpc.JsonUnframedGrpcErrorHandler.ERROR_DETAILS_MARSHALLER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.curioswitch.common.protobuf.json.MessageMarshaller;
import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.grpc.testing.Error.AuthError;
import com.linecorp.armeria.grpc.testing.Error.InternalError;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.grpc.Status;

public class UnframedGrpcErrorHandlerBuilderTest {
    @Test
    void cannotCallRegisterMarshalledMessagesAndJsonMarshallerSimultaneously() {
        assertThatThrownBy(
                () -> UnframedGrpcErrorHandler.builder()
                                              .jsonMarshaller(ERROR_DETAILS_MARSHALLER)
                                              .registerMarshalledMessageTypes(InternalError.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "Cannot register custom messageTypes because a custom JSON marshaller has " +
                        "already been set. Use the custom marshaller to register custom message types.");

        assertThatThrownBy(
                () -> UnframedGrpcErrorHandler.builder()
                                              .jsonMarshaller(ERROR_DETAILS_MARSHALLER)
                                              .registerMarshalledMessages(
                                                      InternalError.newBuilder().build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "Cannot register custom messages because a custom JSON marshaller has " +
                        "already been set. Use the custom marshaller to register custom messages.");

        assertThatThrownBy(
                () -> UnframedGrpcErrorHandler.builder()
                                              .jsonMarshaller(ERROR_DETAILS_MARSHALLER)
                                              .registerMarshalledMessages(
                                                      ImmutableList.of(InternalError.newBuilder().build())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "Cannot register the collection of messages because a custom JSON marshaller has " +
                        "already been set. Use the custom marshaller to register custom messages.");

        assertThatThrownBy(
                () -> UnframedGrpcErrorHandler.builder()
                                              .jsonMarshaller(ERROR_DETAILS_MARSHALLER)
                                              .registerMarshalledMessageTypes(
                                                      ImmutableList.of(InternalError.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "Cannot register the collection of messageTypes because a custom JSON marshaller has " +
                        "already been set. Use the custom marshaller to register custom message types.");

        assertThatThrownBy(
                () -> UnframedGrpcErrorHandler.builder()
                                              .registerMarshalledMessages(InternalError.newBuilder().build())
                                              .jsonMarshaller(ERROR_DETAILS_MARSHALLER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "Cannot set a custom JSON marshaller because one or more Message instances or " +
                        "Message types have already been registered. To set a custom marshaller, " +
                        "ensure that no Message or Message type registrations have been made before " +
                        "calling this method.");
    }

    @Test
    void buildWithoutOptions() {
        final UnframedGrpcErrorHandler unframedGrpcErrorHandler = UnframedGrpcErrorHandler.builder().build();
        final ServiceRequestContext ctx =
                ServiceRequestContext.of(HttpRequest.of(RequestHeaders.of(HttpMethod.GET, "/test")));
        final AggregatedHttpResponse jsonResponse =
                AggregatedHttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR,
                                          MediaType.JSON_UTF_8,
                                          "{\"message\":\"Internal Server Error\"}");
        final HttpResponse httpResponseJson =
                unframedGrpcErrorHandler.handle(ctx, Status.INTERNAL, jsonResponse);
        final AggregatedHttpResponse aggregatedHttpResponse = httpResponseJson.aggregate().join();
        assertThat(aggregatedHttpResponse.headers().contentType().isJson()).isTrue();

        final AggregatedHttpResponse plaintextResponse =
                AggregatedHttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR,
                                          MediaType.PLAIN_TEXT_UTF_8,
                                          "Internal Server Error");
        final HttpResponse httpResponsePlaintext =
                unframedGrpcErrorHandler.handle(ctx, Status.INTERNAL, plaintextResponse);
        assertThat(httpResponsePlaintext.aggregate().join().headers()
                                        .contentType().is(MediaType.PLAIN_TEXT)).isTrue();
    }

    @Test
    void buildWithResponseType() {
        assertThat(
                UnframedGrpcErrorHandler.builder()
                                        .responseTypes(UnframedGrpcErrorResponseType.JSON)
                                        .build()
        ).isInstanceOf(JsonUnframedGrpcErrorHandler.class);

        assertThat(
                UnframedGrpcErrorHandler.builder()
                                        .responseTypes(UnframedGrpcErrorResponseType.PLAINTEXT)
                                        .build()
        ).isInstanceOf(TextUnframedGrpcErrorHandler.class);

        final UnframedGrpcErrorHandler unframedGrpcErrorHandler =
                UnframedGrpcErrorHandler.builder()
                                        .responseTypes(
                                                UnframedGrpcErrorResponseType.JSON,
                                                UnframedGrpcErrorResponseType.PLAINTEXT)
                                        .build();
        final ServiceRequestContext ctx =
                ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/test"));
        final AggregatedHttpResponse jsonResponse =
                AggregatedHttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR,
                                          MediaType.JSON_UTF_8,
                                          "{\"message\":\"Internal Server Error\"}");

        final HttpResponse jsonHttpResponse = unframedGrpcErrorHandler.handle(ctx, Status.INTERNAL,
                                                                              jsonResponse);
        assertThat(jsonHttpResponse.aggregate().join().headers().contentType()
                                   .isJson()).isTrue();
        final AggregatedHttpResponse textResponse =
                AggregatedHttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR,
                                          MediaType.PLAIN_TEXT_UTF_8,
                                          "{\"message\":\"Internal Server Error\"}");
        final HttpResponse textHttpResponse = unframedGrpcErrorHandler.handle(ctx, Status.INTERNAL,
                                                                              textResponse);
        assertThat(textHttpResponse.aggregate().join().headers().contentType()
                                   .is(MediaType.PLAIN_TEXT)).isTrue();
    }

    @Test
    void buildWithCustomJsonMarshaller() {
        final MessageMarshaller messageMarshaller = MessageMarshaller.builder().build();
        assertDoesNotThrow(() -> UnframedGrpcErrorHandler.builder()
                                                         .jsonMarshaller(messageMarshaller)
                                                         .build());
    }

    @Test
    void buildWithCustomMessage() {
        assertDoesNotThrow(() -> UnframedGrpcErrorHandler.builder()
                                                         .registerMarshalledMessageTypes(
                                                                 InternalError.class,
                                                                 AuthError.class)
                                                         .build());
        assertDoesNotThrow(() -> UnframedGrpcErrorHandler.builder()
                                                         .registerMarshalledMessages(
                                                                 InternalError.newBuilder().build(),
                                                                 AuthError.newBuilder().build())
                                                         .build());
        assertDoesNotThrow(() -> UnframedGrpcErrorHandler.builder()
                                                         .registerMarshalledMessageTypes(
                                                                 ImmutableList.of(InternalError.class,
                                                                                  AuthError.class))
                                                         .build());
        assertDoesNotThrow(() -> UnframedGrpcErrorHandler.builder()
                                                         .registerMarshalledMessages(
                                                                 ImmutableList.of(InternalError.newBuilder()
                                                                                               .build(),
                                                                                  AuthError.newBuilder()
                                                                                           .build()))
                                                         .build());
    }
}
