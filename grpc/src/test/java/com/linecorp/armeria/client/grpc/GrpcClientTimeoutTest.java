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

package com.linecorp.armeria.client.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.Uninterruptibles;

import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.grpc.testing.Messages.SimpleRequest;
import com.linecorp.armeria.grpc.testing.Messages.SimpleResponse;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc.TestServiceBlockingStub;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc.TestServiceImplBase;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

class GrpcClientTimeoutTest {

    private static final ch.qos.logback.classic.Logger rootLogger =
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);

    @Mock
    private Appender<ILoggingEvent> appender;

    @Captor
    private ArgumentCaptor<ILoggingEvent> loggingEventCaptor;

    @BeforeEach
    void setupLogger() {
        rootLogger.addAppender(appender);
    }

    @AfterEach
    void cleanupLogger() {
        rootLogger.detachAppender(appender);
    }

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service(GrpcService.builder()
                                  .addService(new SlowService())
                                  .build());
        }
    };

    @Test
    void timeout() throws InterruptedException {
        final TestServiceBlockingStub client =
                Clients.newClient(server.httpUri(GrpcSerializationFormats.PROTO),
                                  TestServiceBlockingStub.class);
        final StatusRuntimeException exception = catchThrowableOfType(() -> {
            client.withDeadlineAfter(1000, TimeUnit.MILLISECONDS)
                  .unaryCall(SimpleRequest.getDefaultInstance());
        }, StatusRuntimeException.class);
        assertThat(exception.getStatus().getCode()).isEqualTo(Code.DEADLINE_EXCEEDED);
        // Wait for a long running task to complete
        Thread.sleep(3000);

        verify(appender, atLeastOnce()).doAppend(loggingEventCaptor.capture());
        assertThat(loggingEventCaptor.getAllValues()).noneMatch(event -> {
            return event.getLevel() == Level.WARN &&
                   event.getThrowableProxy() != null &&
                   event.getThrowableProxy().getMessage().contains("call already closed");
        });
    }

    private static class SlowService extends TestServiceImplBase {
        @Override
        public void unaryCall(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            ServiceRequestContext.current()
                                 .blockingTaskExecutor()
                                 .submit(() -> {
                                     // Defer response
                                     Uninterruptibles.sleepUninterruptibly(2, TimeUnit.SECONDS);
                                     responseObserver.onNext(SimpleResponse.newBuilder()
                                                                           .setUsername("Armeria")
                                                                           .build());
                                     responseObserver.onCompleted();
                                 });
        }
    }
}
