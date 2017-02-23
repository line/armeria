/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.grpc.interop;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.io.IOException;
import java.lang.reflect.Method;
import java.security.cert.CertificateException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.bridge.SLF4JBridgeHandler;

import com.google.protobuf.ByteString;

import com.linecorp.armeria.common.http.HttpSessionProtocols;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcServiceBuilder;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.TestUtils;
import io.grpc.testing.integration.AbstractInteropTest;
import io.grpc.testing.integration.Messages.Payload;
import io.grpc.testing.integration.Messages.PayloadType;
import io.grpc.testing.integration.Messages.ResponseParameters;
import io.grpc.testing.integration.Messages.StreamingOutputCallRequest;
import io.grpc.testing.integration.Messages.StreamingOutputCallResponse;
import io.grpc.testing.integration.TestServiceGrpc;
import io.grpc.testing.integration.TestServiceGrpc.TestServiceStub;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;

/**
 * Interop test based on grpc-interop-testing. Should provide reasonable confidence in armeria's
 * handling of the grpc protocol.
 */
@Ignore // TODO(trustin): Re-enable after upgrading to GRPC 1.1.2.
public class ArmeriaGrpcServerInteropTest extends AbstractInteropTest {

    /** Starts the server with HTTPS. */
    @BeforeClass
    public static void startServer() {
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();

        try {
            SelfSignedCertificate ssc = new SelfSignedCertificate();
            ServerBuilder sb = new ServerBuilder()
                    .port(0, HttpSessionProtocols.HTTPS)
                    .defaultMaxRequestLength(16 * 1024 * 1024)
                    .sslContext(
                            GrpcSslContexts.forServer(ssc.certificate(), ssc.privateKey())
                                           .clientAuth(ClientAuth.REQUIRE)
                                           .trustManager(TestUtils.loadCert("ca.pem"))
                                           .ciphers(TestUtils.preferredTestCiphers(),
                                                    SupportedCipherSuiteFilter.INSTANCE)
                                           .build());
            startStaticServer(new ArmeriaGrpcServerBuilder(sb, new GrpcServiceBuilder()));
        } catch (IOException | CertificateException ex) {
            throw new RuntimeException(ex);
        }
    }

    @AfterClass
    public static void stopServer() {
        stopStaticServer();
    }

    @Override
    protected ManagedChannel createChannel() {
        try {
            // Use reflection to access package-private method.
            Method getPort = AbstractInteropTest.class.getDeclaredMethod("getPort");
            getPort.setAccessible(true);
            return NettyChannelBuilder
                    .forAddress("localhost", (int) getPort.invoke(this))
                    .flowControlWindow(65 * 1024)
                    .maxMessageSize(16 * 1024 * 1024)
                    .sslContext(GrpcSslContexts
                                        .forClient()
                                        .keyManager(TestUtils.loadCert("client.pem"),
                                                    TestUtils.loadCert("client.key"))
                                        .trustManager(InsecureTrustManagerFactory.INSTANCE)
                                        .ciphers(TestUtils.preferredTestCiphers(),
                                                 SupportedCipherSuiteFilter.INSTANCE)
                                        .build())
                    .build();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    // Several tests copied due to Mockito version mismatch (timeout() was moved).

    @Override
    @Test(timeout = 10000)
    public void pingPong() throws Exception {
        final List<StreamingOutputCallRequest> requests = Arrays.asList(
                StreamingOutputCallRequest.newBuilder()
                                          .addResponseParameters(ResponseParameters.newBuilder()
                                                                                   .setSize(31415))
                                          .setPayload(Payload.newBuilder()
                                                             .setBody(ByteString.copyFrom(new byte[27182])))
                                          .build(),
                StreamingOutputCallRequest.newBuilder()
                                          .addResponseParameters(ResponseParameters.newBuilder()
                                                                                   .setSize(9))
                                          .setPayload(Payload.newBuilder()
                                                             .setBody(ByteString.copyFrom(new byte[8])))
                                          .build(),
                StreamingOutputCallRequest.newBuilder()
                                          .addResponseParameters(ResponseParameters.newBuilder()
                                                                                   .setSize(2653))
                                          .setPayload(Payload.newBuilder()
                                                             .setBody(ByteString.copyFrom(new byte[1828])))
                                          .build(),
                StreamingOutputCallRequest.newBuilder()
                                          .addResponseParameters(ResponseParameters.newBuilder()
                                                                                   .setSize(58979))
                                          .setPayload(Payload.newBuilder()
                                                             .setBody(ByteString.copyFrom(new byte[45904])))
                                          .build());
        final List<StreamingOutputCallResponse> goldenResponses = Arrays.asList(
                StreamingOutputCallResponse.newBuilder()
                                           .setPayload(Payload.newBuilder()
                                                              .setType(PayloadType.COMPRESSABLE)
                                                              .setBody(ByteString.copyFrom(new byte[31415])))
                                           .build(),
                StreamingOutputCallResponse.newBuilder()
                                           .setPayload(Payload.newBuilder()
                                                              .setType(PayloadType.COMPRESSABLE)
                                                              .setBody(ByteString.copyFrom(new byte[9])))
                                           .build(),
                StreamingOutputCallResponse.newBuilder()
                                           .setPayload(Payload.newBuilder()
                                                              .setType(PayloadType.COMPRESSABLE)
                                                              .setBody(ByteString.copyFrom(new byte[2653])))
                                           .build(),
                StreamingOutputCallResponse.newBuilder()
                                           .setPayload(Payload.newBuilder()
                                                              .setType(PayloadType.COMPRESSABLE)
                                                              .setBody(ByteString.copyFrom(new byte[58979])))
                                           .build());

        @SuppressWarnings("unchecked")
        StreamObserver<StreamingOutputCallResponse> responseObserver = mock(StreamObserver.class);
        StreamObserver<StreamingOutputCallRequest> requestObserver
                = asyncStub.fullDuplexCall(responseObserver);
        for (int i = 0; i < requests.size(); i++) {
            requestObserver.onNext(requests.get(i));
            verify(responseObserver, timeout(operationTimeoutMillis())).onNext(goldenResponses.get(i));
            verifyNoMoreInteractions(responseObserver);
        }
        requestObserver.onCompleted();
        verify(responseObserver, timeout(operationTimeoutMillis())).onCompleted();
        verifyNoMoreInteractions(responseObserver);
    }

    // FIXME: This doesn't work yet and may require some complicated changes. Armeria should continue to accept
    // requests after a channel is gracefully closed but doesn't appear to (maybe because it supports both
    // HTTP1, which has no concept of graceful shutdown, and HTTP2).
    @Ignore
    @Override
    @Test(timeout = 10000)
    public void gracefulShutdown() throws Exception {
        final List<StreamingOutputCallRequest> requests = Arrays.asList(
                StreamingOutputCallRequest.newBuilder()
                                          .addResponseParameters(ResponseParameters.newBuilder()
                                                                                   .setSize(3))
                                          .setPayload(Payload.newBuilder()
                                                             .setBody(ByteString.copyFrom(new byte[2])))
                                          .build(),
                StreamingOutputCallRequest.newBuilder()
                                          .addResponseParameters(ResponseParameters.newBuilder()
                                                                                   .setSize(1))
                                          .setPayload(Payload.newBuilder()
                                                             .setBody(ByteString.copyFrom(new byte[7])))
                                          .build(),
                StreamingOutputCallRequest.newBuilder()
                                          .addResponseParameters(ResponseParameters.newBuilder()
                                                                                   .setSize(4))
                                          .setPayload(Payload.newBuilder()
                                                             .setBody(ByteString.copyFrom(new byte[1])))
                                          .build());
        final List<StreamingOutputCallResponse> goldenResponses = Arrays.asList(
                StreamingOutputCallResponse.newBuilder()
                                           .setPayload(Payload.newBuilder()
                                                              .setType(PayloadType.COMPRESSABLE)
                                                              .setBody(ByteString.copyFrom(new byte[3])))
                                           .build(),
                StreamingOutputCallResponse.newBuilder()
                                           .setPayload(Payload.newBuilder()
                                                              .setType(PayloadType.COMPRESSABLE)
                                                              .setBody(ByteString.copyFrom(new byte[1])))
                                           .build(),
                StreamingOutputCallResponse.newBuilder()
                                           .setPayload(Payload.newBuilder()
                                                              .setType(PayloadType.COMPRESSABLE)
                                                              .setBody(ByteString.copyFrom(new byte[4])))
                                           .build());

        @SuppressWarnings("unchecked")
        StreamObserver<StreamingOutputCallResponse> responseObserver = mock(StreamObserver.class);
        StreamObserver<StreamingOutputCallRequest> requestObserver
                = asyncStub.fullDuplexCall(responseObserver);
        requestObserver.onNext(requests.get(0));
        verify(responseObserver, timeout(operationTimeoutMillis())).onNext(goldenResponses.get(0));
        // Initiate graceful shutdown.
        channel.shutdown();
        requestObserver.onNext(requests.get(1));
        verify(responseObserver, timeout(operationTimeoutMillis())).onNext(goldenResponses.get(1));
        // The previous ping-pong could have raced with the shutdown, but this one certainly shouldn't.
        requestObserver.onNext(requests.get(2));
        verify(responseObserver, timeout(operationTimeoutMillis())).onNext(goldenResponses.get(2));
        requestObserver.onCompleted();
        verify(responseObserver, timeout(operationTimeoutMillis())).onCompleted();
        verifyNoMoreInteractions(responseObserver);
    }

    @Override
    @Test(timeout = 10000)
    public void timeoutOnSleepingServer() {
        TestServiceStub stub = TestServiceGrpc.newStub(channel)
                                              .withDeadlineAfter(1, TimeUnit.MILLISECONDS);
        @SuppressWarnings("unchecked")
        StreamObserver<StreamingOutputCallResponse> responseObserver = mock(StreamObserver.class);
        StreamObserver<StreamingOutputCallRequest> requestObserver
                = stub.fullDuplexCall(responseObserver);

        try {
            requestObserver.onNext(StreamingOutputCallRequest.newBuilder()
                                                             .setPayload(Payload.newBuilder()
                                                                                .setBody(ByteString.copyFrom(
                                                                                        new byte[27182])))
                                                             .build());
        } catch (IllegalStateException expected) {
            // This can happen if the stream has already been terminated due to deadline exceeded.
        }

        ArgumentCaptor<Throwable> captor = ArgumentCaptor.forClass(Throwable.class);
        verify(responseObserver, timeout(operationTimeoutMillis())).onError(captor.capture());
        assertEquals(Status.DEADLINE_EXCEEDED.getCode(),
                     Status.fromThrowable(captor.getValue()).getCode());
        verifyNoMoreInteractions(responseObserver);
    }

    @Override
    @Test(timeout = 10000)
    public void cancelAfterFirstResponse() throws Exception {
        final StreamingOutputCallRequest request = StreamingOutputCallRequest
                .newBuilder()
                .addResponseParameters(
                        ResponseParameters
                                .newBuilder()
                                .setSize(31415))
                .setPayload(Payload.newBuilder()
                                   .setBody(
                                           ByteString
                                                   .copyFrom(
                                                           new byte[27182])))
                .build();
        final StreamingOutputCallResponse goldenResponse = StreamingOutputCallResponse
                .newBuilder()
                .setPayload(
                        Payload.newBuilder()
                               .setType(
                                       PayloadType.COMPRESSABLE)
                               .setBody(
                                       ByteString
                                               .copyFrom(
                                                       new byte[31415])))
                .build();

        @SuppressWarnings("unchecked")
        StreamObserver<StreamingOutputCallResponse> responseObserver = mock(StreamObserver.class);
        StreamObserver<StreamingOutputCallRequest> requestObserver
                = asyncStub.fullDuplexCall(responseObserver);
        requestObserver.onNext(request);
        verify(responseObserver, timeout(operationTimeoutMillis())).onNext(goldenResponse);
        verifyNoMoreInteractions(responseObserver);

        requestObserver.onError(new RuntimeException());
        ArgumentCaptor<Throwable> captor = ArgumentCaptor.forClass(Throwable.class);
        verify(responseObserver, timeout(operationTimeoutMillis())).onError(captor.capture());
        assertEquals(Status.Code.CANCELLED, Status.fromThrowable(captor.getValue()).getCode());
        verifyNoMoreInteractions(responseObserver);
    }

    @Override
    @Test(timeout = 10000)
    public void emptyStream() throws Exception {
        @SuppressWarnings("unchecked")
        StreamObserver<StreamingOutputCallResponse> responseObserver = mock(StreamObserver.class);
        StreamObserver<StreamingOutputCallRequest> requestObserver
                = asyncStub.fullDuplexCall(responseObserver);
        requestObserver.onCompleted();
        verify(responseObserver, timeout(operationTimeoutMillis())).onCompleted();
        verifyNoMoreInteractions(responseObserver);
    }
}
