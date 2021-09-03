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

package com.linecorp.armeria.server.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.ReleasableHolder;
import com.linecorp.armeria.grpc.testing.Messages.StreamingOutputCallRequest;
import com.linecorp.armeria.grpc.testing.Messages.StreamingOutputCallResponse;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc.TestServiceBlockingStub;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc.TestServiceImplBase;
import com.linecorp.armeria.protobuf.EmptyProtos.Empty;
import com.linecorp.armeria.server.Server;

import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

class ServerCallListenerCompatibilityTest {

    private static final ListenerEventCollector eventCollector = new ListenerEventCollector();

    @BeforeEach
    void setUp() {
        eventCollector.clear();
    }

    @ArgumentsSource(ServiceProvider.class)
    @ParameterizedTest
    void unaryCall(List<ReleasableHolder<TestServiceBlockingStub>> clients) throws InterruptedException {
        @Nullable StatusRuntimeException oldException = null;
        @Nullable List<String> events = null;
        for (int i = 0; i < clients.size(); i++) {
            final ReleasableHolder<TestServiceBlockingStub> resource = clients.get(i);
            final TestServiceBlockingStub client = resource.get();

            try {
                try {
                    client.emptyCall(Empty.getDefaultInstance());
                } catch (Throwable ex) {
                    final StatusRuntimeException newException = (StatusRuntimeException) ex;
                    if (i == 0) {
                        oldException = newException;
                    } else {
                        assertThat(oldException).isNotNull();
                        assertThat(newException).isInstanceOf(oldException.getClass());
                        final Status status = newException.getStatus();
                        final Code code = status.getCode();
                        if (code == Code.DEADLINE_EXCEEDED ||
                            (code == Code.CANCELLED &&
                             "Completed without a response".equals(status.getDescription()))) {
                            // Don't compare the descriptions when:
                            // - a response is incompletely finished, the upstream error does not contain
                            //   description.
                            // - a description about DEADLINE_EXCEEDED might have different deadlines
                            //   depending on OS scheduling and network conditions.
                        } else {
                            assertThat(newException.getMessage()).isEqualTo(oldException.getMessage());
                        }
                    }
                }
                // Waits 1 second for events to be fully collected.
                Thread.sleep(1000);
                if (i == 0) {
                    events = eventCollector.capture();
                } else {
                    final List<String> newEvents = eventCollector.capture();
                    assertThat(events).isNotNull();
                    assertThat(newEvents).isEqualTo(events);
                }
            } finally {
                resource.release();
            }
        }
    }

    @ArgumentsSource(ServiceProvider.class)
    @ParameterizedTest
    void streamCall(List<ReleasableHolder<TestServiceBlockingStub>> clients) throws InterruptedException {
        @Nullable Throwable exception = null;
        boolean hasResponse = false;
        final List<List<String>> allEvents = new ArrayList<>();
        for (int i = 0; i < clients.size(); i++) {
            final ReleasableHolder<TestServiceBlockingStub> resource = clients.get(i);
            final TestServiceBlockingStub client = resource.get();

            try {
                try {
                    final Iterator<StreamingOutputCallResponse> res =
                            client.streamingOutputCall(StreamingOutputCallRequest.getDefaultInstance());
                    // Drain responses.
                    while (res.hasNext()) {
                        res.next();
                        hasResponse = true;
                    }
                } catch (Throwable ex) {
                    final StatusRuntimeException statusException = (StatusRuntimeException) ex;
                    if (i == 0) {
                        exception = statusException;
                    } else {
                        assertThat(exception).isNotNull();
                        assertThat(statusException).isInstanceOf(exception.getClass());
                        if (statusException.getStatus().getCode() != Code.DEADLINE_EXCEEDED) {
                            // A description about a deadline might have a different time decision.
                            assertThat(statusException.getMessage()).isEqualTo(exception.getMessage());
                        }
                    }
                }
                // Waits 1 second for events to be fully collected.
                Thread.sleep(1000);
                allEvents.add(eventCollector.capture());
                if (i > 0) {
                    if (!hasResponse) {
                        assertThat(allEvents.get(i)).isEqualTo(allEvents.get(i - 1));
                    }
                }
            } finally {
                resource.release();
            }

            if (hasResponse) {
                final List<String> grpcJavaEvents = allEvents.get(0);
                final ImmutableList<String> expectedEvents =
                        ImmutableList.of("onReady", "onMessage", "onHalfClose", "onComplete");
                assertThat(grpcJavaEvents).containsExactlyElementsOf(expectedEvents);
                for (int j = 1; j < allEvents.size(); j++) {
                    final List<String> armeriaEvents = allEvents.get(i);
                    assertThat(armeriaEvents).matches(events -> {
                        // A server returned 2 messages. ArmeriaServerCall will invoke onReady() whenever a
                        // message is correctly consumed with OK status. However, the exact number of
                        // `onReady()` varies depending on when messages are emitted.
                        return events.equals(expectedEvents) ||
                               events.equals(ImmutableList.of("onReady", "onMessage", "onHalfClose",
                                                              "onReady", "onComplete")) ||
                               events.equals(ImmutableList.of("onReady", "onMessage", "onHalfClose",
                                                              "onReady", "onReady", "onComplete"));
                    }, "");
                }
            }
        }
    }

    private static final class ExceptionalService extends TestServiceImplBase {
        @Override
        public void emptyCall(Empty request, StreamObserver<Empty> responseObserver) {
            throw new IllegalArgumentException("Boom!");
        }

        @Override
        public void streamingOutputCall(StreamingOutputCallRequest request,
                                        StreamObserver<StreamingOutputCallResponse> responseObserver) {
            throw new IllegalArgumentException("Boom!");
        }
    }

    private static final class CancelingService extends TestServiceImplBase {
        @Override
        public void emptyCall(Empty request, StreamObserver<Empty> responseObserver) {
            responseObserver.onError(Status.CANCELLED.withDescription("cancel").asRuntimeException());
        }

        @Override
        public void streamingOutputCall(StreamingOutputCallRequest request,
                                        StreamObserver<StreamingOutputCallResponse> responseObserver) {
            responseObserver.onError(Status.CANCELLED.withDescription("cancel").asRuntimeException());
        }
    }

    private static final class EmptyResponseService extends TestServiceImplBase {
        @Override
        public void emptyCall(Empty request, StreamObserver<Empty> responseObserver) {
            responseObserver.onCompleted();
        }

        @Override
        public void streamingOutputCall(StreamingOutputCallRequest request,
                                        StreamObserver<StreamingOutputCallResponse> responseObserver) {
            responseObserver.onCompleted();
        }
    }

    private static final class OkService extends TestServiceImplBase {
        @Override
        public void emptyCall(Empty request, StreamObserver<Empty> responseObserver) {
            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
        }

        @Override
        public void streamingOutputCall(StreamingOutputCallRequest request,
                                        StreamObserver<StreamingOutputCallResponse> responseObserver) {
            responseObserver.onNext(StreamingOutputCallResponse.getDefaultInstance());
            responseObserver.onNext(StreamingOutputCallResponse.getDefaultInstance());
            responseObserver.onCompleted();
        }
    }

    private static final class NonOkService extends TestServiceImplBase {
        @Override
        public void emptyCall(Empty request, StreamObserver<Empty> responseObserver) {
            responseObserver.onError(
                    Status.INVALID_ARGUMENT.withDescription("invalid").asRuntimeException());
        }

        @Override
        public void streamingOutputCall(StreamingOutputCallRequest request,
                                        StreamObserver<StreamingOutputCallResponse> responseObserver) {
            responseObserver.onError(
                    Status.INVALID_ARGUMENT.withDescription("invalid").asRuntimeException());
        }
    }

    private static final class SlowService extends TestServiceImplBase {
        @Override
        public void emptyCall(Empty request, StreamObserver<Empty> responseObserver) {
            // A client will cancel the request by a deadline.
        }

        @Override
        public void streamingOutputCall(StreamingOutputCallRequest request,
                                        StreamObserver<StreamingOutputCallResponse> responseObserver) {
            // A client will cancel the request by a deadline.
        }
    }

    private static final class ListenerEventCollector implements ServerInterceptor {

        private final Queue<String> eventQueue = new ArrayDeque<>();

        private List<String> capture() {
            final List<String> captured = ImmutableList.copyOf(eventQueue);
            clear();
            return captured;
        }

        private void clear() {
            eventQueue.clear();
        }

        @Override
        public <I, O> ServerCall.Listener<I> interceptCall(ServerCall<I, O> serverCall,
                                                           Metadata metadata,
                                                           ServerCallHandler<I, O> serverCallHandler) {
            final ServerCall.Listener<I> listener = serverCallHandler.startCall(serverCall, metadata);
            return new EventCollectingServerCallListener<>(listener);
        }

        private final class EventCollectingServerCallListener<I>
                extends ForwardingServerCallListener.SimpleForwardingServerCallListener<I> {

            EventCollectingServerCallListener(ServerCall.Listener<I> listener) {
                super(listener);
            }

            @Override
            public void onMessage(I message) {
                eventQueue.add("onMessage");
                super.onMessage(message);
            }

            @Override
            public void onHalfClose() {
                eventQueue.add("onHalfClose");
                super.onHalfClose();
            }

            @Override
            public void onReady() {
                eventQueue.add("onReady");
                super.onReady();
            }

            @Override
            public void onComplete() {
                eventQueue.add("onComplete");
                super.onComplete();
            }

            @Override
            public void onCancel() {
                eventQueue.add("onCancel");
                super.onCancel();
            }
        }
    }

    private static final class ServiceProvider implements ArgumentsProvider {

        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) throws Exception {
            return Stream.<Supplier<TestServiceImplBase>>of(ExceptionalService::new,
                                                            EmptyResponseService::new,
                                                            CancelingService::new,
                                                            OkService::new,
                                                            NonOkService::new,
                                                            SlowService::new)
                         .map(service -> ImmutableList.of(clientForGrpcJava(service.get()),
                                                          clientForArmeria(service.get(), true, true),
                                                          clientForArmeria(service.get(), false, false),
                                                          clientForArmeria(service.get(), false, true)))
                         .map(Arguments::of);
        }
    }

    private static ReleasableHolder<TestServiceBlockingStub> clientForArmeria(TestServiceImplBase service,
                                                                              boolean useBlocking,
                                                                              boolean useClientTimeout) {

        final Server armeriaServer =
                Server.builder()
                      .requestTimeoutMillis(2000)
                      .service(GrpcService.builder()
                                          .addService(service)
                                          .intercept(eventCollector)
                                          .useClientTimeoutHeader(useClientTimeout)
                                          .useBlockingTaskExecutor(useBlocking)
                                          .build())
                      .build();
        armeriaServer.start().join();

        return new ReleasableHolder<TestServiceBlockingStub>() {
            @Override
            public TestServiceBlockingStub get() {
                return Clients.newClient("gproto+http://127.0.0.1:" + armeriaServer.activeLocalPort(),
                                         TestServiceBlockingStub.class)
                              .withDeadlineAfter(2, TimeUnit.SECONDS);
            }

            @Override
            public void release() {
                armeriaServer.stop().join();
            }

            @Override
            public String toString() {
                return "Armeria(service:" + service.getClass().getSimpleName() +
                       ", blocking:" + useBlocking + ", client-timeout: " + useClientTimeout + ')';
            }
        };
    }

    private static ReleasableHolder<TestServiceBlockingStub> clientForGrpcJava(TestServiceImplBase service) {

        final io.grpc.Server grpcJavaServer =
                io.grpc.ServerBuilder.forPort(0)
                                     .addService(service)
                                     .intercept(eventCollector)
                                     .build();
        try {
            grpcJavaServer.start();
        } catch (IOException e) {
            return Exceptions.throwUnsafely(e);
        }

        return new ReleasableHolder<TestServiceBlockingStub>() {

            @Override
            public TestServiceBlockingStub get() {
                return Clients.newClient("gproto+http://127.0.0.1:" + grpcJavaServer.getPort(),
                                         TestServiceBlockingStub.class)
                              .withDeadlineAfter(2, TimeUnit.SECONDS);
            }

            @Override
            public void release() {
                grpcJavaServer.shutdownNow();
            }

            @Override
            public String toString() {
                return "gRPC-Java(service: " + service.getClass().getSimpleName() + ')';
            }
        };
    }
}
