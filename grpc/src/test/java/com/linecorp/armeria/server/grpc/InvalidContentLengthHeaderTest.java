/*
 * Copyright 2022 LINE Corporation
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.internal.common.grpc.TestServiceImpl;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import testing.grpc.Messages.SimpleRequest;
import testing.grpc.TestServiceGrpc.TestServiceBlockingStub;

class InvalidContentLengthHeaderTest {

    private static final Metadata.Key<String> CONTENT_LENGTH =
            Metadata.Key.of("content-length", Metadata.ASCII_STRING_MARSHALLER);

    private static final AtomicReference<ResponseHeaders> responseHeadersCapture = new AtomicReference<>();

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final ServerInterceptor interceptor = new ServerInterceptor() {
                @Override
                public <I, O> Listener<I> interceptCall(ServerCall<I, O> call,
                                                        Metadata requestHeaders,
                                                        ServerCallHandler<I, O> next) {
                    final Metadata metadata = new Metadata();
                    metadata.put(CONTENT_LENGTH, "555");
                    call.close(Status.UNAUTHENTICATED, metadata);
                    return new Listener<I>() {};
                }
            };

            final GrpcService grpcService =
                    GrpcService.builder()
                               .addService(new TestServiceImpl(CommonPools.blockingTaskExecutor()))
                               .intercept(interceptor)
                               .build();

            sb.service(grpcService);
            sb.decorator((delegate, ctx, req) -> {
                final HttpResponse res = delegate.serve(ctx, req);
                // Capture response headers.
                return res.mapHeaders(headers -> {
                    responseHeadersCapture.set(headers);
                    return headers;
                });
            });
        }
    };

    @Test
    void contentLengthRemovedFromAdditionalTrailers() {
        final TestServiceBlockingStub client =
                GrpcClients.newClient(server.httpUri(),
                                     TestServiceBlockingStub.class);

        assertThatThrownBy(() -> {
            client.unaryCall(SimpleRequest.newBuilder().setResponseSize(100).build());
        }).isInstanceOf(StatusRuntimeException.class)
          .hasMessageContaining("UNAUTHENTICATED");

        final ResponseHeaders serverResponseHeaders = responseHeadersCapture.get();
        assertThat(serverResponseHeaders).isNotNull();

        // The mistakenly set content-length (500) should have been removed by statusToTrailers().
        final String contentLength = serverResponseHeaders.get(HttpHeaderNames.CONTENT_LENGTH);
        assertThat(contentLength).isEqualTo("0");
        assertThat(serverResponseHeaders.isEndOfStream()).isTrue();
    }
}
