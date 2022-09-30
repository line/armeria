/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.grpc.java;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.Test;

import com.google.common.util.concurrent.ListenableFuture;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.grpc.java.Hello.HelloReply;
import com.linecorp.armeria.grpc.java.Hello.HelloRequest;
import com.linecorp.armeria.grpc.java.HelloServiceGrpc.HelloServiceFutureStub;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;

class ClientHttp2GracefulShutdownTimeoutOverridingTest {

    @Test
    void idleTimeoutIsUsedForHttp2GracefulShutdownTimeout() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final Server server = ServerBuilder.forPort(0)
                                           .addService(new HelloSleepService(latch))
                                           .build()
                                           .start();

        try (ClientFactory factory = ClientFactory.builder()
                                                  // Set greater than 40 seconds for HTTP/2 graceful shutdown.
                                                  .idleTimeout(Duration.ofSeconds(50))
                                                  .build()) {
            final HelloServiceFutureStub client =
                    GrpcClients.builder("http://127.0.0.1:" + server.getPort())
                               .responseTimeout(Duration.ofSeconds(120))
                               .factory(factory)
                               .build(HelloServiceFutureStub.class);

            final ListenableFuture<HelloReply> responseFuture = client.hello(
                    HelloRequest.newBuilder().setName("hello").build());
            latch.await();
            server.shutdown();
            final HelloReply helloReply = responseFuture.get();
            assertThat(helloReply.getMessage()).isEqualTo("hello");
            server.awaitTermination();
        }
    }

    private static class HelloSleepService extends HelloServiceImpl {

        private final CountDownLatch latch;

        HelloSleepService(CountDownLatch latch) {
            this.latch = latch;
        }

        @Override
        public void hello(HelloRequest request, StreamObserver<HelloReply> responseObserver) {
            latch.countDown();
            try {
                // Return after 40 seconds that is bigger than the default
                // HTTP/2 timeout 30 seconds which is defined at
                // Http2CodecUtil.DEFAULT_GRACEFUL_SHUTDOWN_TIMEOUT_MILLIS
                Thread.sleep(40000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            responseObserver.onNext(Hello.HelloReply.newBuilder().setMessage(request.getName()).build());
            responseObserver.onCompleted();
        }
    }
}
