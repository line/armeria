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

package com.linecorp.armeria.client.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;

import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
import testing.grpc.Messages.SimpleRequest;
import testing.grpc.TestServiceGrpc.TestServiceBlockingStub;

class InvalidGrpcResponseTest {

    @Test
    void shouldFailIfGrpcStatusIsMissingForOkHttpStatus() {
        final TestServiceBlockingStub client =
                GrpcClients.builder("http://127.0.0.1:8080")
                           .decorator((delegate, ctx, req) -> {
                               // Return a headers-only response without "grpc-status".
                               return HttpResponse.of(ResponseHeaders.of(HttpStatus.OK));
                           })
                           .build(TestServiceBlockingStub.class);
        final StatusRuntimeException cause =
                catchThrowableOfType(() -> client.unaryCall(SimpleRequest.getDefaultInstance()),
                                     StatusRuntimeException.class);
        assertThat(cause).hasMessageContaining("Missing gRPC status code");
        assertThat(cause.getStatus().getCode()).isEqualTo(Code.INTERNAL);
    }

    @Test
    void allowNoGrpcStatusForNonOkHttpStatus() {
        final TestServiceBlockingStub client =
                GrpcClients.builder("http://127.0.0.1:8080")
                           .decorator((delegate, ctx, req) -> {
                               // Return a headers-only response without "grpc-status".
                               return HttpResponse.of(ResponseHeaders.of(HttpStatus.UNAUTHORIZED));
                           })
                           .build(TestServiceBlockingStub.class);
        final StatusRuntimeException cause =
                catchThrowableOfType(() -> client.unaryCall(SimpleRequest.getDefaultInstance()),
                                     StatusRuntimeException.class);
        assertThat(cause).hasMessageContaining("UNAUTHENTICATED: HTTP status code 401");
        assertThat(cause.getStatus().getCode()).isEqualTo(Code.UNAUTHENTICATED);
    }
}
