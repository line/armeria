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

import static com.linecorp.armeria.internal.common.grpc.GrpcTestUtil.REQUEST_MESSAGE;
import static com.linecorp.armeria.internal.common.grpc.GrpcTestUtil.RESPONSE_MESSAGE;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedTransferQueue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.Codec;
import io.grpc.DecompressorRegistry;
import io.grpc.stub.StreamObserver;
import testing.grpc.Messages.SimpleRequest;
import testing.grpc.Messages.SimpleResponse;
import testing.grpc.UnitTestServiceGrpc.UnitTestServiceBlockingStub;
import testing.grpc.UnitTestServiceGrpc.UnitTestServiceImplBase;

public class GrpcServiceAutoCompressTest {

    @RegisterExtension
    static final ServerExtension autoCompressionServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.workerGroup(1);
            sb.requestTimeoutMillis(5000);
            sb.serviceUnder("/",
                            GrpcService.builder()
                                       .autoCompression(true)
                                       .addService(new UnitTestServiceImpl())
                                       .build()
                                       .decorate(LoggingService.newDecorator())
                                       .decorate((delegate, ctx, req) -> {
                                           ctx.log().whenComplete().thenAccept(requestLogQueue::add);
                                           return delegate.serve(ctx, req);
                                       }));
        }
    };

    private static BlockingQueue<RequestLog> requestLogQueue = new LinkedTransferQueue<>();

    @BeforeEach
    void setUp() {
        requestLogQueue = new LinkedTransferQueue<>();
    }

    @Test
    void autoCompression() throws Exception {
        final UnitTestServiceBlockingStub client = GrpcClients.newClient(autoCompressionServer.httpUri(),
                                                                         UnitTestServiceBlockingStub.class);
        assertThat(client.staticUnaryCall(REQUEST_MESSAGE)).isEqualTo(RESPONSE_MESSAGE);
        final RequestLog log = requestLogQueue.take();
        assertThat(log.requestHeaders().get("grpc-accept-encoding")).isEqualTo("gzip");
        assertThat(log.responseHeaders().get("grpc-encoding")).isEqualTo("gzip");
    }

    @Test
    void autoCompressionWithMultipleAcceptEncoding() throws Exception {
        final DecompressorRegistry decompressorRegistry = DecompressorRegistry.emptyInstance()
                                                                              .with(new Codec.Gzip(), true)
                                                                              .with(Codec.Identity.NONE, true);
        final UnitTestServiceBlockingStub client = GrpcClients.builder(autoCompressionServer.httpUri())
                                                              .decompressorRegistry(decompressorRegistry)
                                                              .build(UnitTestServiceBlockingStub.class);
        assertThat(client.staticUnaryCall(REQUEST_MESSAGE)).isEqualTo(RESPONSE_MESSAGE);
        final RequestLog log = requestLogQueue.take();
        assertThat(log.requestHeaders().get("grpc-accept-encoding")).isEqualTo("gzip,identity");
        assertThat(log.responseHeaders().get("grpc-encoding")).isEqualTo("gzip");
    }

    private static class UnitTestServiceImpl extends UnitTestServiceImplBase {

        @Override
        public void staticUnaryCall(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            if (!request.equals(REQUEST_MESSAGE)) {
                responseObserver.onError(new IllegalArgumentException("Unexpected request: " + request));
                return;
            }
            responseObserver.onNext(RESPONSE_MESSAGE);
            responseObserver.onCompleted();
        }
    }
}
