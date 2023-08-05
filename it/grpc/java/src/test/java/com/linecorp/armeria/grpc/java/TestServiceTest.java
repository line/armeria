/*
 *  Copyright 2022 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.grpc.java;

import static com.linecorp.armeria.grpc.java.TestServiceImpl.toMessage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.base.Stopwatch;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.stub.StreamObserver;
import testing.grpc.Hello.HelloReply;
import testing.grpc.Hello.HelloRequest;
import testing.grpc.TestServiceGrpc.TestServiceBlockingStub;
import testing.grpc.TestServiceGrpc.TestServiceFutureStub;
import testing.grpc.TestServiceGrpc.TestServiceStub;

class TestServiceTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service(GrpcService.builder()
                                  .addService(new TestServiceImpl())
                                  .enableUnframedRequests(true)
                                  .build());
        }
    };

    @Test
    void getReply() {
        final TestServiceBlockingStub TestService =
                GrpcClients.newClient(uri(), TestServiceBlockingStub.class);
        assertThat(TestService.hello(HelloRequest.newBuilder().setName("Armeria").build()).getMessage())
                .isEqualTo("Hello, Armeria!");
    }

    @Test
    void getReplyWithDelay() {
        final TestServiceFutureStub TestService = GrpcClients.newClient(uri(), TestServiceFutureStub.class);
        final ListenableFuture<HelloReply> future =
                TestService.lazyHello(HelloRequest.newBuilder().setName("Armeria").build());
        final AtomicBoolean completed = new AtomicBoolean();
        Futures.addCallback(future, new FutureCallback<HelloReply>() {
            @Override
            public void onSuccess(HelloReply result) {
                assertThat(result.getMessage()).isEqualTo("Hello, Armeria!");
                completed.set(true);
            }

            @Override
            public void onFailure(Throwable t) {
                // Should never reach here.
                throw new Error(t);
            }
        }, MoreExecutors.directExecutor());

        await().untilTrue(completed);
    }

    @Test
    void getReplyFromServerSideBlockingCall() {
        final TestServiceBlockingStub TestService =
                GrpcClients.newClient(uri(), TestServiceBlockingStub.class);
        final Stopwatch watch = Stopwatch.createStarted();
        assertThat(TestService.blockingHello(HelloRequest.newBuilder().setName("Armeria").build())
                               .getMessage()).isEqualTo("Hello, Armeria!");
        assertThat(watch.elapsed(TimeUnit.SECONDS)).isGreaterThanOrEqualTo(3);
    }

    @Test
    void getLotsOfReplies() {
        final TestServiceStub TestService = service();
        final AtomicBoolean completed = new AtomicBoolean();
        final AtomicInteger sequence = new AtomicInteger();
        TestService.lotsOfReplies(
                HelloRequest.newBuilder().setName("Armeria").build(),
                new StreamObserver<HelloReply>() {

                    @Override
                    public void onNext(HelloReply value) {
                        assertThat(value.getMessage())
                                .isEqualTo("Hello, Armeria! (sequence: " + sequence.incrementAndGet() + ')');
                    }

                    @Override
                    public void onError(Throwable t) {
                        // Should never reach here.
                        throw new Error(t);
                    }

                    @Override
                    public void onCompleted() {
                        assertThat(sequence).hasValue(5);
                        completed.set(true);
                    }
                });
        await().atMost(Duration.ofSeconds(15))
               .untilAsserted(() -> assertThat(completed)
                       .overridingErrorMessage(() -> "sequence is " + sequence)
                       .isTrue());
    }

    @Test
    void blockForLotsOfReplies() throws Exception {
        final TestServiceStub TestService = service();
        final BlockingQueue<HelloReply> replies = new LinkedBlockingQueue<>();
        final AtomicBoolean completed = new AtomicBoolean();
        TestService.lotsOfReplies(
                HelloRequest.newBuilder().setName("Armeria").build(),
                new StreamObserver<HelloReply>() {

                    @Override
                    public void onNext(HelloReply value) {
                        replies.offer(value);
                    }

                    @Override
                    public void onError(Throwable t) {
                        // Should never reach here.
                        throw new Error(t);
                    }

                    @Override
                    public void onCompleted() {
                        completed.set(true);
                    }
                });
        int sequence = 0;
        while (!completed.get() || !replies.isEmpty()) {
            final HelloReply value = replies.poll(100, TimeUnit.MILLISECONDS);
            if (value == null) {
                // Timed out, try again.
                continue;
            }
            assertThat(value.getMessage())
                    .isEqualTo("Hello, Armeria! (sequence: " + ++sequence + ')');
        }
        assertThat(sequence).isEqualTo(5);
    }

    @Test
    void sendLotsOfGreetings() {
        final TestServiceStub TestService = service();
        final String[] names = { "Armeria", "Grpc", "Streaming" };
        final AtomicBoolean completed = new AtomicBoolean();
        final StreamObserver<HelloRequest> request =
                TestService.lotsOfGreetings(new StreamObserver<HelloReply>() {
                    private boolean received;

                    @Override
                    public void onNext(HelloReply value) {
                        assertThat(received).isFalse();
                        received = true;
                        assertThat(value.getMessage())
                                .isEqualTo(toMessage(String.join(", ", names)));
                    }

                    @Override
                    public void onError(Throwable t) {
                        // Should never reach here.
                        throw new Error(t);
                    }

                    @Override
                    public void onCompleted() {
                        assertThat(received).isTrue();
                        completed.set(true);
                    }
                });

        for (String name : names) {
            request.onNext(HelloRequest.newBuilder().setName(name).build());
        }
        request.onCompleted();
        await().untilTrue(completed);
    }

    @Test
    void bidirectionalHello() {
        final TestServiceStub TestService = service();
        final String[] names = { "Armeria", "Grpc", "Streaming" };
        final AtomicBoolean completed = new AtomicBoolean();
        final StreamObserver<HelloRequest> request =
                TestService.bidiHello(new StreamObserver<HelloReply>() {
                    private int received;

                    @Override
                    public void onNext(HelloReply value) {
                        assertThat(value.getMessage())
                                .isEqualTo(toMessage(names[received++]));
                    }

                    @Override
                    public void onError(Throwable t) {
                        // Should never reach here.
                        throw new Error(t);
                    }

                    @Override
                    public void onCompleted() {
                        assertThat(received).isEqualTo(names.length);
                        completed.set(true);
                    }
                });

        for (String name : names) {
            request.onNext(HelloRequest.newBuilder().setName(name).build());
        }
        request.onCompleted();
        await().untilTrue(completed);
    }

    private static TestServiceStub service() {
        return GrpcClients.newClient(uri(), TestServiceStub.class);
    }

    private static String uri() {
        return server.httpUri(GrpcSerializationFormats.PROTO).toString();
    }
}
