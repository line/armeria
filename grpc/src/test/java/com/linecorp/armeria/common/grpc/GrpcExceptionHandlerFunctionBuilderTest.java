/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.common.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.Status.Code;

class GrpcExceptionHandlerFunctionBuilderTest {

    private static final Metadata.Key<String> TEST_KEY =
            Metadata.Key.of("test_key", Metadata.ASCII_STRING_MARSHALLER);

    private static final Metadata.Key<String> TEST_KEY2 =
            Metadata.Key.of("test_key2", Metadata.ASCII_STRING_MARSHALLER);
    private static final ServiceRequestContext ctx =
            ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));

    @Test
    void duplicatedExceptionHandlers() {
        final GrpcExceptionHandlerFunctionBuilder builder = GrpcExceptionHandlerFunction.builder();
        builder.on(A1Exception.class, Status.RESOURCE_EXHAUSTED);

        assertThatThrownBy(() -> {
            builder.on(A1Exception.class, (ctx, throwable, metadata) -> Status.UNIMPLEMENTED);
        }).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("is already added with");

        assertThatThrownBy(() -> {
            builder.on(A1Exception.class, Status.UNIMPLEMENTED);
        }).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("is already added with");
    }

    @Test
    void sortExceptionHandler() {
        final GrpcExceptionHandlerFunctionBuilder builder = GrpcExceptionHandlerFunction.builder();
        builder.on(A1Exception.class, (ctx, throwable, metadata) -> Status.RESOURCE_EXHAUSTED);
        builder.on(A2Exception.class, (ctx, throwable, metadata) -> Status.UNIMPLEMENTED);

        assertThat(builder.exceptionMappings.stream().map(it -> (Class) it.getKey()))
                .containsExactly(A2Exception.class, A1Exception.class);

        builder.on(B1Exception.class, (ctx, throwable, metadata) -> Status.UNAUTHENTICATED);
        assertThat(builder.exceptionMappings.stream().map(it -> (Class) it.getKey()))
                .containsExactly(A2Exception.class,
                                 A1Exception.class,
                                 B1Exception.class);

        builder.on(A3Exception.class, (ctx, throwable, metadata) -> Status.UNAUTHENTICATED);
        assertThat(builder.exceptionMappings.stream().map(it -> (Class) it.getKey()))
                .containsExactly(A3Exception.class,
                                 A2Exception.class,
                                 A1Exception.class,
                                 B1Exception.class);

        builder.on(B2Exception.class, (ctx, throwable, metadata) -> Status.NOT_FOUND);
        assertThat(builder.exceptionMappings.stream().map(it -> (Class) it.getKey()))
                .containsExactly(A3Exception.class,
                                 A2Exception.class,
                                 A1Exception.class,
                                 B2Exception.class,
                                 B1Exception.class);

        final GrpcExceptionHandlerFunction exceptionHandler = builder.build().orElse(
                GrpcExceptionHandlerFunction.of());
        Status status = exceptionHandler.apply(ctx, new A3Exception(), new Metadata());
        assertThat(status.getCode()).isEqualTo(Code.UNAUTHENTICATED);

        status = exceptionHandler.apply(ctx, new A2Exception(), new Metadata());
        assertThat(status.getCode()).isEqualTo(Code.UNIMPLEMENTED);

        status = exceptionHandler.apply(ctx, new A1Exception(), new Metadata());
        assertThat(status.getCode()).isEqualTo(Code.RESOURCE_EXHAUSTED);

        status = exceptionHandler.apply(ctx, new B2Exception(), new Metadata());
        assertThat(status.getCode()).isEqualTo(Code.NOT_FOUND);

        status = exceptionHandler.apply(ctx, new B1Exception(), new Metadata());
        assertThat(status.getCode()).isEqualTo(Code.UNAUTHENTICATED);
    }

    @Test
    void mapStatus() {
        final GrpcExceptionHandlerFunction exceptionHandler =
                GrpcExceptionHandlerFunction
                        .builder()
                        .on(A2Exception.class, (ctx, throwable, metadata) -> Status.PERMISSION_DENIED)
                        .on(A1Exception.class, (ctx1, cause, metadata) -> Status.DEADLINE_EXCEEDED)
                        .build();

        for (Throwable ex : ImmutableList.of(new A2Exception(), new A3Exception())) {
            final Metadata metadata = new Metadata();
            final Status newStatus = exceptionHandler.apply(ctx, ex, metadata);
            assertThat(newStatus.getCode()).isEqualTo(Code.PERMISSION_DENIED);
            assertThat(newStatus.getCause()).isEqualTo(ex);
            assertThat(metadata.keys()).isEmpty();
        }

        final A1Exception cause = new A1Exception();
        final Metadata metadata = new Metadata();
        final Status newStatus = exceptionHandler.apply(ctx, cause, metadata);

        assertThat(newStatus.getCode()).isEqualTo(Code.DEADLINE_EXCEEDED);
        assertThat(newStatus.getCause()).isEqualTo(cause);
        assertThat(metadata.keys()).isEmpty();
    }

    @Test
    void mapStatusAndMetadata() {
        final GrpcExceptionHandlerFunction exceptionHandler =
                GrpcExceptionHandlerFunction
                        .builder()
                        .on(B1Exception.class, (ctx, throwable, metadata) -> {
                            metadata.put(TEST_KEY, throwable.getClass().getSimpleName());
                            return Status.ABORTED;
                        })
                        .build();

        final B1Exception cause = new B1Exception();
        final Metadata metadata1 = new Metadata();
        final Status newStatus1 = exceptionHandler.apply(ctx, cause, metadata1);
        assertThat(newStatus1.getCode()).isEqualTo(Code.ABORTED);
        assertThat(metadata1.get(TEST_KEY)).isEqualTo("B1Exception");
        assertThat(metadata1.keys()).containsOnly(TEST_KEY.name());

        final Metadata metadata2 = new Metadata();
        metadata2.put(TEST_KEY2, "test");
        final Status newStatus2 = exceptionHandler.apply(ctx, cause, metadata2);

        assertThat(newStatus2.getCode()).isEqualTo(Code.ABORTED);
        assertThat(metadata2.get(TEST_KEY)).isEqualTo("B1Exception");
        assertThat(metadata2.keys()).containsOnly(TEST_KEY.name(), TEST_KEY2.name());
    }

    private static class A1Exception extends RuntimeException {
        private static final long serialVersionUID = 7571363504756974018L;
    }

    private static class A2Exception extends A1Exception {
        private static final long serialVersionUID = 6081251455350756572L;
    }

    private static class A3Exception extends A2Exception {
        private static final long serialVersionUID = -5833147108943992843L;
    }

    private static class B1Exception extends RuntimeException {
        private static final long serialVersionUID = 7171673234824312473L;
    }

    private static class B2Exception extends B1Exception {
        private static final long serialVersionUID = -3517806360215313260L;
    }
}
