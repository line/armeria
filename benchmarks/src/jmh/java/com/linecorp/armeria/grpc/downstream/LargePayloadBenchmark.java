/*
 * Copyright 2018 LINE Corporation
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

package com.linecorp.armeria.grpc.downstream;

import java.util.concurrent.CountDownLatch;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import com.google.protobuf.ByteString;
import com.google.protobuf.util.Timestamps;

import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.grpc.BinaryProxyGrpc.BinaryProxyImplBase;
import com.linecorp.armeria.grpc.BinaryProxyGrpc.BinaryProxyStub;
import com.linecorp.armeria.grpc.BinaryProxyOuterClass.BinaryPayload;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.unsafe.grpc.GrpcUnsafeBufferUtil;

import io.grpc.BindableService;
import io.grpc.stub.StreamObserver;
import joptsimple.internal.Strings;

/**
 * A {@link Benchmark} to check performance of armeria-grpc with large payloads in the megabytes, which is
 * a relatively common use case for a binary proxy (metadata + large binary blobs).
 */
@State(Scope.Benchmark)
public class LargePayloadBenchmark {

    // 4MB payload
    private static final BinaryPayload PAYLOAD =
            BinaryPayload.newBuilder()
                         .setTimeReceived(Timestamps.fromMillis(10000000L))
                         .setPayload(ByteString.copyFromUtf8(Strings.repeat('a', 4_000_000)))
                         .build();

    private Server server;
    private BindableService bindableService;
    private BinaryProxyStub binaryProxyClient;

    @Param({ "false", "true" })
    private boolean wrapBuffer;

    @Setup
    public void setUp() {

        bindableService = new BinaryProxyImplBase() {
            @Override
            public StreamObserver<BinaryPayload> echo(StreamObserver<BinaryPayload> responseObserver) {
                return new StreamObserver<BinaryPayload>() {
                    @Override
                    public void onNext(BinaryPayload value) {
                        try {
                            responseObserver.onNext(value);
                        } finally {
                            if (wrapBuffer) {
                                GrpcUnsafeBufferUtil.releaseBuffer(value, ServiceRequestContext.current());
                            }
                        }
                    }

                    @Override
                    public void onError(Throwable t) {
                        responseObserver.onError(t);
                    }

                    @Override
                    public void onCompleted() {
                        responseObserver.onCompleted();
                    }
                };
            }
        };

        server = Server.builder()
                       .serviceUnder("/",
                                     GrpcService.builder()
                                                .addService(bindableService)
                                                .unsafeWrapRequestBuffers(wrapBuffer)
                                                .build())
                       .build();
        server.start().join();

        final String url = "gproto+http://127.0.0.1:" + server.activeLocalPort(SessionProtocol.HTTP) + '/';
        binaryProxyClient = Clients.newClient(url, BinaryProxyStub.class);
    }

    @TearDown
    public void tearDown() {
        server.stop().join();
    }

    @Benchmark
    public boolean normal() throws Exception {
        final EchoObserver responseObserver = new EchoObserver();
        final StreamObserver<BinaryPayload> requestObserver = binaryProxyClient.echo(responseObserver);
        requestObserver.onNext(PAYLOAD);
        requestObserver.onNext(PAYLOAD);
        // TODO(anuraag): Figure out why 3 requests doesn't work.
        requestObserver.onCompleted();
        return responseObserver.finish(2);
    }

    private static final class EchoObserver implements StreamObserver<BinaryPayload> {

        private final CountDownLatch latch = new CountDownLatch(1);

        private volatile Throwable error;
        private volatile int num;

        public boolean finish(int expected) throws Exception {
            latch.await();
            if (error != null) {
                throw new RuntimeException(error);
            }
            if (num != expected) {
                throw new IllegalStateException("Unexpected num: " + num);
            }
            return true;
        }

        @Override
        public void onNext(BinaryPayload value) {
            num++;
        }

        @Override
        public void onError(Throwable t) {
            error = t;
            latch.countDown();
        }

        @Override
        public void onCompleted() {
            latch.countDown();
        }
    }

    public static void main(String[] args) throws Exception {
        final LargePayloadBenchmark benchmark = new LargePayloadBenchmark();
        benchmark.setUp();
        try {
            benchmark.normal();
        } finally {
            benchmark.tearDown();
        }
    }
}
