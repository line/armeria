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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.grpc.testing.Messages.SimpleRequest;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc.TestServiceBlockingStub;
import com.linecorp.armeria.internal.common.grpc.TestServiceImpl;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

class GrpcServerInterceptorTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service(GrpcService.builder()
                                  .addService(ServerInterceptors.intercept(
                                          new TestServiceImpl(Executors.newSingleThreadScheduledExecutor()),
                                          NoPassInterceptor.INSTANCE))
                                  .build());
        }
    };

    @Test
    void closeCallByInterceptor() {
        final TestServiceBlockingStub client =
                Clients.builder(server.httpUri(GrpcSerializationFormats.PROTO))
                       .build(TestServiceBlockingStub.class);
        final Throwable cause = catchThrowable(() -> client.unaryCall(SimpleRequest.getDefaultInstance()));
        assertThat(cause).isInstanceOf(StatusRuntimeException.class);
        assertThat(((StatusRuntimeException) cause).getStatus()).isEqualTo(Status.PERMISSION_DENIED);
    }

    private static class NoPassInterceptor implements ServerInterceptor {

        private static final NoPassInterceptor INSTANCE = new NoPassInterceptor();

        private static final Listener<Object> NOOP_LISTENER = new ServerCall.Listener<Object>() {};

        @Override
        public <I, O> Listener<I> interceptCall(ServerCall<I, O> call, Metadata metadata,
                                                ServerCallHandler<I, O> next) {
            call.close(Status.PERMISSION_DENIED, metadata);
            @SuppressWarnings("unchecked")
            final Listener<I> cast = (Listener<I>) NOOP_LISTENER;
            return cast;
        }
    }
}
