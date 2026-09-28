/*
 * Copyright 2026 LY Corporation
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

package com.linecorp.armeria.it.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import testing.grpc.FlowControlTestServiceGrpc.FlowControlTestServiceImplBase;
import testing.grpc.FlowControlTestServiceGrpc.FlowControlTestServiceStub;
import testing.grpc.Messages.SimpleRequest;
import testing.grpc.Messages.SimpleResponse;

/**
 * {@link ServerCallStreamObserver#setOnReadyHandler(Runnable)} guarantees that the handler runs
 * serialized with the inbound {@link StreamObserver} callbacks. A service that writes a response from
 * its onReady handler and clears the state guarding that write afterwards relies on the handler not
 * being reentered while it is still running; grpc-java's own {@code ProtoReflectionServiceV1} is
 * written that way.
 *
 * <p>This test uses a service of that shape and asserts that one request produces exactly one
 * response.
 */
@Timeout(30)
class GrpcOnReadyReentrancyTest {

    private static final int NUM_REQUESTS = 6;

    private static final SimpleRequest REQUEST = SimpleRequest.newBuilder().build();
    private static final SimpleResponse RESPONSE = SimpleResponse.newBuilder().build();

    private static final AtomicInteger responsesWritten = new AtomicInteger();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service(GrpcService.builder()
                                  .addService(new ReflectionStyleService())
                                  .build());
        }
    };

    @Test
    void oneRequestProducesOneResponse() throws Exception {
        responsesWritten.set(0);

        final FlowControlTestServiceStub client = GrpcClients.newClient(server.httpUri(),
                                                                        FlowControlTestServiceStub.class);
        final AtomicInteger responsesReceived = new AtomicInteger();
        final AtomicInteger requestsSent = new AtomicInteger();
        final CompletableFuture<Void> closed = new CompletableFuture<>();
        final AtomicReference<StreamObserver<SimpleRequest>> requestObserverRef = new AtomicReference<>();

        final StreamObserver<SimpleRequest> requestObserver = client.noBackPressure(
                new StreamObserver<SimpleResponse>() {
                    @Override
                    public void onNext(SimpleResponse value) {
                        responsesReceived.incrementAndGet();
                        // Keep the stream busy: a single request does not reproduce the reentrancy,
                        // because there is no demand for the response yet when the first one is written.
                        if (requestsSent.get() < NUM_REQUESTS) {
                            requestsSent.incrementAndGet();
                            requestObserverRef.get().onNext(REQUEST);
                            if (requestsSent.get() == NUM_REQUESTS) {
                                requestObserverRef.get().onCompleted();
                            }
                        }
                    }

                    @Override
                    public void onError(Throwable t) {
                        closed.completeExceptionally(t);
                    }

                    @Override
                    public void onCompleted() {
                        closed.complete(null);
                    }
                });
        requestObserverRef.set(requestObserver);

        requestsSent.incrementAndGet();
        requestObserver.onNext(REQUEST);

        closed.get(20, TimeUnit.SECONDS);

        assertThat(requestsSent).hasValue(NUM_REQUESTS);
        assertThat(responsesWritten).hasValue(NUM_REQUESTS);
        assertThat(responsesReceived).hasValue(NUM_REQUESTS);
    }

    /**
     * Mirrors the shape of {@code ProtoReflectionServiceV1}: the onReady handler writes a response while
     * {@code request} is set, and clears {@code request} only after the write.
     */
    private static final class ReflectionStyleService extends FlowControlTestServiceImplBase {

        @Override
        public StreamObserver<SimpleRequest> noBackPressure(StreamObserver<SimpleResponse> rawObserver) {
            final ServerCallStreamObserver<SimpleResponse> observer =
                    (ServerCallStreamObserver<SimpleResponse>) rawObserver;
            final ReflectionStyleObserver requestObserver = new ReflectionStyleObserver(observer);
            observer.setOnReadyHandler(requestObserver);
            observer.disableAutoRequest();
            observer.request(1);
            return requestObserver;
        }
    }

    private static final class ReflectionStyleObserver
            implements Runnable, StreamObserver<SimpleRequest> {

        private final ServerCallStreamObserver<SimpleResponse> observer;
        private boolean closeAfterSend;
        @Nullable
        private SimpleRequest request;

        ReflectionStyleObserver(ServerCallStreamObserver<SimpleResponse> observer) {
            this.observer = observer;
        }

        @Override
        public void run() {
            // The onReady handler. Reentered while `request` is still set, it writes the response again.
            if (request != null) {
                handleRequest();
            }
        }

        @Override
        public void onNext(SimpleRequest value) {
            request = value;
            handleRequest();
        }

        @Override
        public void onError(Throwable t) {}

        @Override
        public void onCompleted() {
            closeAfterSend = true;
            if (request == null) {
                observer.onCompleted();
            }
        }

        private void handleRequest() {
            if (observer.isReady()) {
                observer.onNext(RESPONSE);
                responsesWritten.incrementAndGet();
                request = null;
                if (closeAfterSend) {
                    observer.onCompleted();
                } else {
                    observer.request(1);
                }
            }
        }
    }
}
