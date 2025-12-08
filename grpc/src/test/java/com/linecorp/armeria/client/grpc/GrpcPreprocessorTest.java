/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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
import static org.assertj.core.api.Assertions.catchException;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.internal.client.DefaultClientRequestContext;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import testing.grpc.EmptyProtos.Empty;
import testing.grpc.TestServiceGrpc.TestServiceBlockingStub;

class GrpcPreprocessorTest {

    @Test
    void throwCompletesContext() {
        final RuntimeException exception = new RuntimeException("test");
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final TestServiceBlockingStub stub = GrpcClients.builder((delegate, ctx, req) -> {
                throw exception;
            }).build(TestServiceBlockingStub.class);

            final Exception thrown = catchException(() -> stub.emptyCall(Empty.getDefaultInstance()));
            assertThat(thrown).isInstanceOf(StatusRuntimeException.class);
            assertThat((StatusRuntimeException) thrown).hasCause(exception);
            final DefaultClientRequestContext ctx = (DefaultClientRequestContext) captor.get();
            await().untilAsserted(() -> assertThat(ctx.whenInitialized()).isDone());
            await().untilAsserted(() -> assertThat(ctx.log().isComplete()).isTrue());
            assertThat(ctx.eventLoop()).isNotNull();
        }
    }

    @Test
    void cancelCompletesContext() {
        final RuntimeException exception = new RuntimeException("test");
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final TestServiceBlockingStub stub = GrpcClients.builder((delegate, ctx, req) -> {
                return HttpResponse.of(CompletableFuture.supplyAsync(() -> {
                    ctx.cancel();
                    throw exception;
                }));
            }).build(TestServiceBlockingStub.class);

            final Exception thrown = catchException(() -> stub.emptyCall(Empty.getDefaultInstance()));
            assertThat(thrown).isInstanceOf(StatusRuntimeException.class);
            final Status status = ((StatusRuntimeException) thrown).getStatus();
            assertThat(status.getCause()).isSameAs(exception);
            final DefaultClientRequestContext ctx = (DefaultClientRequestContext) captor.get();
            await().untilAsserted(() -> assertThat(ctx.whenInitialized()).isDone());
            await().untilAsserted(() -> assertThat(ctx.log().isComplete()).isTrue());
            assertThat(ctx.eventLoop()).isNotNull();
        }
    }
}
