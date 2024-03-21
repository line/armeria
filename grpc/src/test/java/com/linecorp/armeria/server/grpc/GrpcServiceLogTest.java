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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.internal.common.grpc.StreamRecorder;
import com.linecorp.armeria.internal.common.grpc.TestServiceImpl;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.logging.AccessLogWriter;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import testing.grpc.EmptyProtos.Empty;
import testing.grpc.Messages.StreamingInputCallRequest;
import testing.grpc.Messages.StreamingInputCallResponse;
import testing.grpc.TestServiceGrpc;
import testing.grpc.TestServiceGrpc.TestServiceBlockingStub;
import testing.grpc.TestServiceGrpc.TestServiceStub;

class GrpcServiceLogTest {

    private static final ch.qos.logback.classic.Logger rootLogger =
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final GrpcService grpcService =
                    GrpcService.builder()
                               .addService(new FooTestServiceImpl(Executors.newSingleThreadScheduledExecutor()))
                               .build();

            sb.accessLogWriter(AccessLogWriter.combined(), true);
            sb.serviceUnder("/grpc/", grpcService);
            sb.route()
              .pathPrefix("/default-names")
              .defaultServiceName("DefaultServiceName")
              .defaultLogName("DefaultName")
              .build(grpcService);
        }
    };

    @Mock
    private Appender<ILoggingEvent> appender;
    @Captor
    private ArgumentCaptor<ILoggingEvent> eventCaptor;

    @BeforeEach
    void setupLogger() {
        rootLogger.addAppender(appender);
    }

    @AfterEach
    void cleanupLogger() {
        rootLogger.detachAppender(appender);
    }

    @Test
    void logName() throws InterruptedException {
        final TestServiceBlockingStub client =
                GrpcClients.builder(server.httpUri().resolve("/grpc/"))
                           .build(TestServiceBlockingStub.class);
        client.emptyCall(Empty.newBuilder().build());

        final RequestLog log = server.requestContextCaptor().take().log().whenComplete().join();
        assertThat(log.serviceName()).isEqualTo(TestServiceGrpc.SERVICE_NAME);
        assertThat(log.name()).isEqualTo("EmptyCall");
        assertThat(log.fullName()).isEqualTo(TestServiceGrpc.getEmptyCallMethod().getFullMethodName());
    }

    @Test
    void defaultNames() throws InterruptedException {
        final TestServiceBlockingStub client =
                GrpcClients.builder(server.httpUri().resolve("/default-names/"))
                           .build(TestServiceBlockingStub.class);
        client.emptyCall(Empty.newBuilder().build());

        final RequestLog log = server.requestContextCaptor().take().log().whenComplete().join();
        assertThat(log.serviceName()).isEqualTo("DefaultServiceName");
        assertThat(log.name()).isEqualTo("DefaultName");
        assertThat(log.fullName()).isEqualTo("DefaultServiceName/DefaultName");
    }

    @Test
    void logNameInAccessLog() {
        final TestServiceBlockingStub client =
                GrpcClients.builder(server.httpUri().resolve("/grpc/"))
                           .build(TestServiceBlockingStub.class);
        client.emptyCall(Empty.newBuilder().build());

        await().untilAsserted(() -> {
            verify(appender, atLeast(0)).doAppend(eventCaptor.capture());
            assertThat(eventCaptor.getAllValues()).anyMatch(evt -> {
                return evt.getMessage().contains("POST /grpc/armeria.grpc.testing.TestService/EmptyCall h2c");
            });
        });
    }

    @Test
    void logNameInClientSide() {
        final TestServiceBlockingStub client =
                GrpcClients.builder(server.httpUri().resolve("/grpc/"))
                           .build(TestServiceBlockingStub.class);
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            client.emptyCall(Empty.newBuilder().build());
            final ClientRequestContext ctx = captor.get();
            final RequestLog requestLog = ctx.log().whenComplete().join();
            assertThat(requestLog.serviceName()).isEqualTo(TestServiceGrpc.SERVICE_NAME);
            assertThat(requestLog.name()).isEqualTo("EmptyCall");
        }
    }

    @Test
    void sameStatusExceptionIsUsedForLogAndRequestAbortion() throws Exception {
        final TestServiceStub client =
                GrpcClients.builder(server.httpUri().resolve("/grpc/"))
                           .build(TestServiceStub.class);

        final StreamRecorder<StreamingInputCallResponse> responseObserver = StreamRecorder.create();
        final StreamObserver<StreamingInputCallRequest> requestObserver =
                client.streamingInputCall(responseObserver);

        requestObserver.onNext(StreamingInputCallRequest.getDefaultInstance());
        responseObserver.awaitCompletion();
        // StatusRuntimeException is created by io.grpc.stub.ClientCalls
        assertThat(responseObserver.getError()).isInstanceOf(StatusRuntimeException.class);

        final RequestLog log = server.requestContextCaptor().take().log().whenComplete().join();
        assertThatThrownBy(() -> log.context().request().whenComplete().join())
                .cause() // Unwrap CompletionException.
                .isInstanceOf(StatusRuntimeException.class)
                .isSameAs(log.responseCause());
    }

    private static class FooTestServiceImpl extends TestServiceImpl {

        /**
         * Constructs a controller using the given executor for scheduling response stream chunks.
         */
        FooTestServiceImpl(ScheduledExecutorService executor) {
            super(executor);
        }

        @Override
        public StreamObserver<StreamingInputCallRequest> streamingInputCall(
                StreamObserver<StreamingInputCallResponse> responseObserver) {
            return new StreamObserver<StreamingInputCallRequest>() {

                @Override
                public void onNext(StreamingInputCallRequest value) {
                    responseObserver.onError(new IllegalArgumentException());
                }

                @Override
                public void onError(Throwable t) {}

                @Override
                public void onCompleted() {}
            };
        }
    }
}
