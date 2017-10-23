/*
 * Copyright 2017 LINE Corporation
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

import static com.linecorp.armeria.common.SessionProtocol.HTTP;
import static com.linecorp.armeria.grpc.testing.Messages.PayloadType.COMPRESSABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Ignore;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import com.google.common.base.Throwables;
import com.google.protobuf.ByteString;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientOption;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.common.FilteredHttpResponse;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.util.EventLoopGroups;
import com.linecorp.armeria.grpc.testing.Messages.EchoStatus;
import com.linecorp.armeria.grpc.testing.Messages.Payload;
import com.linecorp.armeria.grpc.testing.Messages.ResponseParameters;
import com.linecorp.armeria.grpc.testing.Messages.SimpleRequest;
import com.linecorp.armeria.grpc.testing.Messages.SimpleResponse;
import com.linecorp.armeria.grpc.testing.Messages.StreamingInputCallRequest;
import com.linecorp.armeria.grpc.testing.Messages.StreamingInputCallResponse;
import com.linecorp.armeria.grpc.testing.Messages.StreamingOutputCallRequest;
import com.linecorp.armeria.grpc.testing.Messages.StreamingOutputCallResponse;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc.TestServiceBlockingStub;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc.TestServiceStub;
import com.linecorp.armeria.grpc.testing.UnimplementedServiceGrpc;
import com.linecorp.armeria.internal.grpc.GrpcHeaderNames;
import com.linecorp.armeria.internal.grpc.StreamRecorder;
import com.linecorp.armeria.internal.grpc.TestServiceImpl;
import com.linecorp.armeria.internal.grpc.TimeoutHeaderUtil;
import com.linecorp.armeria.protobuf.EmptyProtos.Empty;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcServiceBuilder;
import com.linecorp.armeria.testing.server.ServerRule;

import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

public class GrpcClientTest {

    /**
     * Must be at least {@link #unaryPayloadLength()}, plus some to account for encoding overhead.
     */
    private static final int MAX_MESSAGE_SIZE = 16 * 1024 * 1024;

    private static final Empty EMPTY = Empty.getDefaultInstance();

    private static final AtomicReference<HttpHeaders> CLIENT_HEADERS_CAPTURE = new AtomicReference<>();
    private static final AtomicReference<HttpHeaders> SERVER_TRAILERS_CAPTURE = new AtomicReference<>();

    @ClassRule
    public static ServerRule server = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.workerGroup(EventLoopGroups.newEventLoopGroup(1), true);
            sb.port(0, HTTP);
            sb.defaultMaxRequestLength(MAX_MESSAGE_SIZE);

            sb.serviceUnder("/", new GrpcServiceBuilder()
                    .addService(new TestServiceImpl(Executors.newSingleThreadScheduledExecutor()))
                    .setMaxInboundMessageSizeBytes(MAX_MESSAGE_SIZE)
                    .setMaxOutboundMessageSizeBytes(MAX_MESSAGE_SIZE)
                    .build()
                    .decorate(TestServiceImpl.EchoRequestHeadersInTrailers::new)
                    .decorate((client, ctx, req) -> {
                        CLIENT_HEADERS_CAPTURE.set(req.headers());
                        HttpResponse res = client.serve(ctx, req);
                        return new FilteredHttpResponse(res) {

                            private boolean headersReceived;

                            @Override
                            protected HttpObject filter(HttpObject obj) {
                                if (obj instanceof HttpHeaders) {
                                    if (!headersReceived) {
                                        headersReceived = true;
                                    } else {
                                        SERVER_TRAILERS_CAPTURE.set((HttpHeaders) obj);
                                    }
                                }
                                return obj;
                            }
                        };
                    }));
        }
    };

    private TestServiceBlockingStub blockingStub;
    private TestServiceStub asyncStub;

    @Before
    public void setUp() {
        blockingStub = ClientFactory.DEFAULT
                .newClient("gproto+" + server.httpUri("/"), TestServiceBlockingStub.class,
                           ClientOption.DEFAULT_MAX_RESPONSE_LENGTH.newValue((long) MAX_MESSAGE_SIZE));
        asyncStub = ClientFactory.DEFAULT
                .newClient("gproto+" + server.httpUri("/"),
                           TestServiceStub.class);
    }

    @After
    public void tearDown() {
        CLIENT_HEADERS_CAPTURE.set(null);
        SERVER_TRAILERS_CAPTURE.set(null);
    }

    @Test(timeout = 10000)
    public void emptyUnary() throws Exception {
        assertThat(blockingStub.emptyCall(EMPTY)).isEqualTo(EMPTY);
    }

    @Test(timeout = 10000)
    public void largeUnary() throws Exception {
        final SimpleRequest request =
                SimpleRequest.newBuilder()
                             .setResponseSize(314159)
                             .setResponseType(COMPRESSABLE)
                             .setPayload(Payload.newBuilder()
                                                .setBody(ByteString.copyFrom(new byte[271828])))
                             .build();
        final SimpleResponse goldenResponse =
                SimpleResponse.newBuilder()
                              .setPayload(Payload.newBuilder()
                                                 .setType(COMPRESSABLE)
                                                 .setBody(ByteString.copyFrom(new byte[314159])))
                              .build();

        assertThat(blockingStub.unaryCall(request)).isEqualTo(goldenResponse);
    }

    @Test(timeout = 10000)
    public void serverStreaming() throws Exception {
        final StreamingOutputCallRequest request =
                StreamingOutputCallRequest.newBuilder()
                                          .setResponseType(COMPRESSABLE)
                                          .addResponseParameters(
                                                  ResponseParameters.newBuilder()
                                                                    .setSize(31415))
                                          .addResponseParameters(ResponseParameters.newBuilder()
                                                                                   .setSize(9))
                                          .addResponseParameters(ResponseParameters.newBuilder()
                                                                                   .setSize(2653))
                                          .addResponseParameters(ResponseParameters.newBuilder()
                                                                                   .setSize(58979))
                                          .build();
        final List<StreamingOutputCallResponse> goldenResponses = Arrays.asList(
                StreamingOutputCallResponse.newBuilder()
                                           .setPayload(Payload.newBuilder()
                                                              .setType(COMPRESSABLE)
                                                              .setBody(ByteString.copyFrom(new byte[31415])))
                                           .build(),
                StreamingOutputCallResponse.newBuilder()
                                           .setPayload(Payload.newBuilder()
                                                              .setType(COMPRESSABLE)
                                                              .setBody(ByteString.copyFrom(new byte[9])))
                                           .build(),
                StreamingOutputCallResponse.newBuilder()
                                           .setPayload(Payload.newBuilder()
                                                              .setType(COMPRESSABLE)
                                                              .setBody(ByteString.copyFrom(new byte[2653])))
                                           .build(),
                StreamingOutputCallResponse.newBuilder()
                                           .setPayload(Payload.newBuilder()
                                                              .setType(COMPRESSABLE)
                                                              .setBody(ByteString.copyFrom(new byte[58979])))
                                           .build());

        StreamRecorder<StreamingOutputCallResponse> recorder = StreamRecorder.create();
        asyncStub.streamingOutputCall(request, recorder);
        recorder.awaitCompletion();
        assertSuccess(recorder);
        assertThat(recorder.getValues()).containsExactlyElementsOf(goldenResponses);
    }

    @Test(timeout = 10000)
    public void clientStreaming() throws Exception {
        final List<StreamingInputCallRequest> requests = Arrays.asList(
                StreamingInputCallRequest.newBuilder()
                                         .setPayload(Payload.newBuilder()
                                                            .setBody(ByteString.copyFrom(new byte[27182])))
                                         .build(),
                StreamingInputCallRequest.newBuilder()
                                         .setPayload(Payload.newBuilder()
                                                            .setBody(ByteString.copyFrom(new byte[8])))
                                         .build(),
                StreamingInputCallRequest.newBuilder()
                                         .setPayload(Payload.newBuilder()
                                                            .setBody(ByteString.copyFrom(new byte[1828])))
                                         .build(),
                StreamingInputCallRequest.newBuilder()
                                         .setPayload(Payload.newBuilder()
                                                            .setBody(ByteString.copyFrom(new byte[45904])))
                                         .build());
        final StreamingInputCallResponse goldenResponse =
                StreamingInputCallResponse.newBuilder()
                                          .setAggregatedPayloadSize(74922)
                                          .build();

        StreamRecorder<StreamingInputCallResponse> responseObserver = StreamRecorder.create();
        StreamObserver<StreamingInputCallRequest> requestObserver =
                asyncStub.streamingInputCall(responseObserver);
        for (StreamingInputCallRequest request : requests) {
            requestObserver.onNext(request);
        }
        requestObserver.onCompleted();
        assertThat(responseObserver.firstValue().get()).isEqualTo(goldenResponse);
        responseObserver.awaitCompletion();
    }

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
                                                              .setType(COMPRESSABLE)
                                                              .setBody(ByteString.copyFrom(new byte[31415])))
                                           .build(),
                StreamingOutputCallResponse.newBuilder()
                                           .setPayload(Payload.newBuilder()
                                                              .setType(COMPRESSABLE)
                                                              .setBody(ByteString.copyFrom(new byte[9])))
                                           .build(),
                StreamingOutputCallResponse.newBuilder()
                                           .setPayload(Payload.newBuilder()
                                                              .setType(COMPRESSABLE)
                                                              .setBody(ByteString.copyFrom(new byte[2653])))
                                           .build(),
                StreamingOutputCallResponse.newBuilder()
                                           .setPayload(Payload.newBuilder()
                                                              .setType(COMPRESSABLE)
                                                              .setBody(ByteString.copyFrom(new byte[58979])))
                                           .build());

        final ArrayBlockingQueue<Object> queue = new ArrayBlockingQueue<>(5);
        StreamObserver<StreamingOutputCallRequest> requestObserver
                = asyncStub.fullDuplexCall(new StreamObserver<StreamingOutputCallResponse>() {
            @Override
            public void onNext(StreamingOutputCallResponse response) {
                queue.add(response);
            }

            @Override
            public void onError(Throwable t) {
                queue.add(t);
            }

            @Override
            public void onCompleted() {
                queue.add("Completed");
            }
        });
        for (int i = 0; i < requests.size(); i++) {
            assertThat(queue.peek()).isNull();
            requestObserver.onNext(requests.get(i));
            assertThat(queue.poll(operationTimeoutMillis(), TimeUnit.MILLISECONDS)).isEqualTo(
                    goldenResponses.get(i));
        }
        requestObserver.onCompleted();
        assertThat(queue.poll(operationTimeoutMillis(), TimeUnit.MILLISECONDS)).isEqualTo("Completed");
    }

    @Test(timeout = 10000)
    public void emptyStream() throws Exception {
        StreamRecorder<StreamingOutputCallResponse> responseObserver = StreamRecorder.create();
        StreamObserver<StreamingOutputCallRequest> requestObserver
                = asyncStub.fullDuplexCall(responseObserver);
        requestObserver.onCompleted();
        responseObserver.awaitCompletion(operationTimeoutMillis(), TimeUnit.MILLISECONDS);
    }

    @Test(timeout = 10000)
    public void cancelAfterBegin() throws Exception {
        StreamRecorder<StreamingInputCallResponse> responseObserver = StreamRecorder.create();
        StreamObserver<StreamingInputCallRequest> requestObserver =
                asyncStub.streamingInputCall(responseObserver);
        requestObserver.onError(new RuntimeException());
        responseObserver.awaitCompletion();
        assertThat(responseObserver.getValues()).isEmpty();
        assertThat(Status.fromThrowable(responseObserver.getError()).getCode()).isEqualTo(Code.CANCELLED);
    }

    @Test(timeout = 10000)
    public void cancelAfterFirstResponse() throws Exception {
        final StreamingOutputCallRequest request =
                StreamingOutputCallRequest.newBuilder()
                                          .addResponseParameters(ResponseParameters.newBuilder()
                                                                                   .setSize(31415))
                                          .setPayload(Payload.newBuilder()
                                                             .setBody(ByteString.copyFrom(new byte[27182])))
                                          .build();
        final StreamingOutputCallResponse goldenResponse =
                StreamingOutputCallResponse.newBuilder()
                                           .setPayload(Payload.newBuilder()
                                                              .setType(
                                                                      COMPRESSABLE)
                                                              .setBody(ByteString.copyFrom(new byte[31415])))
                                           .build();

        StreamRecorder<StreamingOutputCallResponse> responseObserver = StreamRecorder.create();
        StreamObserver<StreamingOutputCallRequest> requestObserver
                = asyncStub.fullDuplexCall(responseObserver);
        requestObserver.onNext(request);
        await().untilAsserted(() -> assertThat(responseObserver.firstValue().get()).isEqualTo(goldenResponse));
        requestObserver.onError(new RuntimeException());
        responseObserver.awaitCompletion(operationTimeoutMillis(), TimeUnit.MILLISECONDS);
        assertThat(responseObserver.getValues()).hasSize(1);
        assertThat(Status.fromThrowable(responseObserver.getError()).getCode()).isEqualTo(Code.CANCELLED);
    }

    @Test(timeout = 10000)
    public void fullDuplexCallShouldSucceed() throws Exception {
        // Build the request.
        List<Integer> responseSizes = Arrays.asList(50, 100, 150, 200);
        StreamingOutputCallRequest.Builder streamingOutputBuilder =
                StreamingOutputCallRequest.newBuilder();
        streamingOutputBuilder.setResponseType(COMPRESSABLE);
        for (Integer size : responseSizes) {
            streamingOutputBuilder.addResponseParametersBuilder().setSize(size).setIntervalUs(0);
        }
        final StreamingOutputCallRequest request =
                streamingOutputBuilder.build();

        StreamRecorder<StreamingOutputCallResponse> recorder = StreamRecorder.create();
        StreamObserver<StreamingOutputCallRequest> requestStream =
                asyncStub.fullDuplexCall(recorder);

        final int numRequests = 10;
        List<StreamingOutputCallRequest> requests =
                new ArrayList<>(numRequests);
        for (int ix = numRequests; ix > 0; --ix) {
            requests.add(request);
            requestStream.onNext(request);
        }
        requestStream.onCompleted();
        recorder.awaitCompletion();
        assertSuccess(recorder);
        assertThat(recorder.getValues()).hasSize(responseSizes.size() * numRequests);
        for (int ix = 0; ix < recorder.getValues().size(); ++ix) {
            StreamingOutputCallResponse response = recorder.getValues().get(ix);
            assertThat(response.getPayload().getType()).isEqualTo(COMPRESSABLE);
            int length = response.getPayload().getBody().size();
            int expectedSize = responseSizes.get(ix % responseSizes.size());
            assertThat(length).isEqualTo(expectedSize).withFailMessage("comparison failed at index " + ix);
        }
    }

    @Test(timeout = 10000)
    public void halfDuplexCallShouldSucceed() throws Exception {
        // Build the request.
        List<Integer> responseSizes = Arrays.asList(50, 100, 150, 200);
        StreamingOutputCallRequest.Builder streamingOutputBuilder =
                StreamingOutputCallRequest.newBuilder();
        streamingOutputBuilder.setResponseType(COMPRESSABLE);
        for (Integer size : responseSizes) {
            streamingOutputBuilder.addResponseParametersBuilder().setSize(size).setIntervalUs(0);
        }
        final StreamingOutputCallRequest request =
                streamingOutputBuilder.build();

        StreamRecorder<StreamingOutputCallResponse> recorder = StreamRecorder.create();
        StreamObserver<StreamingOutputCallRequest> requestStream = asyncStub.halfDuplexCall(recorder);

        final int numRequests = 10;
        List<StreamingOutputCallRequest> requests = new ArrayList<>(numRequests);
        for (int ix = numRequests; ix > 0; --ix) {
            requests.add(request);
            requestStream.onNext(request);
        }
        requestStream.onCompleted();
        recorder.awaitCompletion();
        assertSuccess(recorder);
        assertThat(recorder.getValues()).hasSize(responseSizes.size() * numRequests);
        for (int ix = 0; ix < recorder.getValues().size(); ++ix) {
            StreamingOutputCallResponse response = recorder.getValues().get(ix);
            assertThat(response.getPayload().getType()).isEqualTo(COMPRESSABLE);
            int length = response.getPayload().getBody().size();
            int expectedSize = responseSizes.get(ix % responseSizes.size());
            assertThat(length).isEqualTo(expectedSize).withFailMessage("comparison failed at index " + ix);
        }
    }

    @Test(timeout = 10000)
    public void serverStreamingShouldBeFlowControlled() throws Exception {
        final StreamingOutputCallRequest request =
                StreamingOutputCallRequest.newBuilder()
                                          .setResponseType(COMPRESSABLE)
                                          .addResponseParameters(
                                                  ResponseParameters.newBuilder().setSize(100000))
                                          .addResponseParameters(
                                                  ResponseParameters.newBuilder().setSize(100001))
                                          .build();
        final List<StreamingOutputCallResponse> goldenResponses = Arrays.asList(
                StreamingOutputCallResponse.newBuilder()
                                           .setPayload(Payload.newBuilder()
                                                              .setType(COMPRESSABLE)
                                                              .setBody(ByteString.copyFrom(new byte[100000])))
                                           .build(),
                StreamingOutputCallResponse.newBuilder()
                                           .setPayload(Payload.newBuilder()
                                                              .setType(COMPRESSABLE)
                                                              .setBody(ByteString.copyFrom(new byte[100001])))
                                           .build());

        long start = System.nanoTime();

        final ArrayBlockingQueue<Object> queue = new ArrayBlockingQueue<>(10);
        ClientCall<StreamingOutputCallRequest, StreamingOutputCallResponse> call =
                asyncStub.getChannel().newCall(TestServiceGrpc.METHOD_STREAMING_OUTPUT_CALL,
                                               CallOptions.DEFAULT);
        call.start(new ClientCall.Listener<StreamingOutputCallResponse>() {
            @Override
            public void onHeaders(Metadata headers) {}

            @Override
            public void onMessage(final StreamingOutputCallResponse message) {
                queue.add(message);
            }

            @Override
            public void onClose(Status status, Metadata trailers) {
                queue.add(status);
            }
        }, new Metadata());
        call.sendMessage(request);
        call.halfClose();

        // Time how long it takes to get the first response.
        call.request(1);
        assertThat(queue.poll(operationTimeoutMillis(), TimeUnit.MILLISECONDS)).isEqualTo(
                goldenResponses.get(0));
        long firstCallDuration = System.nanoTime() - start;

        // Without giving additional flow control, make sure that we don't get another response. We wait
        // until we are comfortable the next message isn't coming. We may have very low nanoTime
        // resolution (like on Windows) or be using a testing, in-process transport where message
        // handling is instantaneous. In both cases, firstCallDuration may be 0, so round up sleep time
        // to at least 1ms.
        assertThat(queue.poll(Math.max(firstCallDuration * 4, 1_000_000), TimeUnit.NANOSECONDS)).isNull();

        // Make sure that everything still completes.
        call.request(1);
        assertThat(queue.poll(operationTimeoutMillis(), TimeUnit.MILLISECONDS)).isEqualTo(
                goldenResponses.get(1));
        assertThat(queue.poll(operationTimeoutMillis(), TimeUnit.MILLISECONDS)).isEqualTo(Status.OK);
        call.cancel("Cancelled after all of the requests are done", null);
    }

    @Test(timeout = 30000)
    public void veryLargeRequest() throws Exception {
        final SimpleRequest request =
                SimpleRequest.newBuilder()
                             .setPayload(Payload.newBuilder()
                                                .setType(COMPRESSABLE)
                                                .setBody(ByteString.copyFrom(new byte[unaryPayloadLength()])))
                             .setResponseSize(10)
                             .setResponseType(COMPRESSABLE)
                             .build();
        final SimpleResponse goldenResponse =
                SimpleResponse.newBuilder()
                              .setPayload(Payload.newBuilder()
                                                 .setType(COMPRESSABLE)
                                                 .setBody(ByteString.copyFrom(new byte[10])))
                              .build();
        assertThat(blockingStub.unaryCall(request)).isEqualTo(goldenResponse);
    }

    @Test(timeout = 30000)
    public void veryLargeResponse() throws Exception {
        final SimpleRequest request =
                SimpleRequest.newBuilder()
                             .setResponseSize(unaryPayloadLength())
                             .setResponseType(COMPRESSABLE)
                             .build();
        final SimpleResponse goldenResponse =
                SimpleResponse.newBuilder()
                              .setPayload(Payload.newBuilder()
                                                 .setType(COMPRESSABLE)
                                                 .setBody(ByteString.copyFrom(new byte[unaryPayloadLength()])))
                              .build();
        assertThat(blockingStub.unaryCall(request)).isEqualTo(goldenResponse);
    }

    @Test(timeout = 10000)
    public void exchangeHeadersUnaryCall() throws Exception {
        TestServiceBlockingStub stub =
                Clients.newDerivedClient(
                        blockingStub,
                        ClientOption.HTTP_HEADERS.newValue(
                                HttpHeaders.of()
                                           .set(TestServiceImpl.EXTRA_HEADER_NAME, "dog")));

        assertThat(stub.emptyCall(EMPTY)).isNotNull();

        // Assert that our side channel object is echoed back in both headers and trailers
        assertThat(CLIENT_HEADERS_CAPTURE.get().get(TestServiceImpl.EXTRA_HEADER_NAME)).isEqualTo("dog");
        assertThat(SERVER_TRAILERS_CAPTURE.get().get(TestServiceImpl.EXTRA_HEADER_NAME)).isEqualTo("dog");
    }

    @Test(timeout = 10000)
    public void exchangeHeadersStreamingCall() throws Exception {
        TestServiceStub stub =
                Clients.newDerivedClient(
                        asyncStub,
                        ClientOption.HTTP_HEADERS.newValue(
                                HttpHeaders.of()
                                           .set(TestServiceImpl.EXTRA_HEADER_NAME, "dog")));

        List<Integer> responseSizes = Arrays.asList(50, 100, 150, 200);
        StreamingOutputCallRequest.Builder streamingOutputBuilder =
                StreamingOutputCallRequest.newBuilder();
        streamingOutputBuilder.setResponseType(COMPRESSABLE);
        for (Integer size : responseSizes) {
            streamingOutputBuilder.addResponseParametersBuilder().setSize(size).setIntervalUs(0);
        }
        final StreamingOutputCallRequest request = streamingOutputBuilder.build();

        StreamRecorder<StreamingOutputCallResponse> recorder = StreamRecorder.create();
        StreamObserver<StreamingOutputCallRequest> requestStream =
                stub.fullDuplexCall(recorder);

        final int numRequests = 10;
        List<StreamingOutputCallRequest> requests = new ArrayList<>(numRequests);

        for (int ix = numRequests; ix > 0; --ix) {
            requests.add(request);
            requestStream.onNext(request);
        }
        requestStream.onCompleted();
        recorder.awaitCompletion();
        assertSuccess(recorder);
        assertThat(recorder.getValues()).hasSize(responseSizes.size() * numRequests);

        // Assert that our side channel object is echoed back in both headers and trailers
        assertThat(CLIENT_HEADERS_CAPTURE.get().get(TestServiceImpl.EXTRA_HEADER_NAME)).isEqualTo("dog");
        assertThat(SERVER_TRAILERS_CAPTURE.get().get(TestServiceImpl.EXTRA_HEADER_NAME)).isEqualTo("dog");
    }

    @Test(timeout = 10000)
    public void sendsTimeoutHeader() {
        long configuredTimeoutMinutes = 100;
        TestServiceBlockingStub stub =
                Clients.newDerivedClient(
                        blockingStub,
                        ClientOption.DEFAULT_RESPONSE_TIMEOUT_MILLIS.newValue(
                                TimeUnit.MINUTES.toMillis(configuredTimeoutMinutes)));
        stub.emptyCall(EMPTY);
        long transferredTimeoutMinutes = TimeUnit.NANOSECONDS.toMinutes(
                TimeoutHeaderUtil.fromHeaderValue(
                        CLIENT_HEADERS_CAPTURE.get().get(GrpcHeaderNames.GRPC_TIMEOUT)));
        assertThat(transferredTimeoutMinutes).isEqualTo(configuredTimeoutMinutes);
    }

    @Test
    public void deadlineNotExceeded() {
        // warm up the channel and JVM
        blockingStub.emptyCall(Empty.getDefaultInstance());
        TestServiceBlockingStub stub =
                Clients.newDerivedClient(
                        blockingStub,
                        ClientOption.DEFAULT_RESPONSE_TIMEOUT_MILLIS.newValue(
                                TimeUnit.SECONDS.toMillis(10)));
        stub
                .streamingOutputCall(
                        StreamingOutputCallRequest.newBuilder()
                                                  .addResponseParameters(
                                                          ResponseParameters.newBuilder()
                                                                            .setIntervalUs(0))
                                                  .build())
                .next();
    }

    @Test(timeout = 10000)
    public void deadlineExceeded() {
        // warm up the channel and JVM
        blockingStub.emptyCall(Empty.getDefaultInstance());
        TestServiceBlockingStub stub =
                Clients.newDerivedClient(
                        blockingStub,
                        ClientOption.DEFAULT_RESPONSE_TIMEOUT_MILLIS.newValue(10L));
        StreamingOutputCallRequest request =
                StreamingOutputCallRequest.newBuilder()
                                          .addResponseParameters(
                                                  ResponseParameters.newBuilder()
                                                                    .setIntervalUs(20000))
                                          .build();
        Throwable t = catchThrowable(() -> stub.streamingOutputCall(request).next());
        assertThat(t).isInstanceOf(StatusRuntimeException.class);
        assertThat(((StatusRuntimeException) t).getStatus().getCode())
                .isEqualTo(Status.DEADLINE_EXCEEDED.getCode());
    }

    @Test(timeout = 10000)
    public void deadlineExceededServerStreaming() throws Exception {
        // warm up the channel and JVM
        blockingStub.emptyCall(Empty.getDefaultInstance());
        ResponseParameters.Builder responseParameters = ResponseParameters.newBuilder()
                                                                          .setSize(1)
                                                                          .setIntervalUs(20000);
        StreamingOutputCallRequest request =
                StreamingOutputCallRequest.newBuilder()
                                          .setResponseType(COMPRESSABLE)
                                          .addResponseParameters(responseParameters)
                                          .addResponseParameters(responseParameters)
                                          .addResponseParameters(responseParameters)
                                          .addResponseParameters(responseParameters)
                                          .build();
        StreamRecorder<StreamingOutputCallResponse> recorder = StreamRecorder.create();
        TestServiceStub stub =
                Clients.newDerivedClient(
                        asyncStub,
                        ClientOption.DEFAULT_RESPONSE_TIMEOUT_MILLIS.newValue(30L));
        stub.streamingOutputCall(request, recorder);
        recorder.awaitCompletion();

        assertThat(recorder.getError()).isNotNull();
        assertThat(Status.fromThrowable(recorder.getError()).getCode())
                .isEqualTo(Status.DEADLINE_EXCEEDED.getCode());
    }

    // NB: It's unclear when anyone would set a negative timeout, and trying to set the negative timeout
    // into a header correctly raises an exception. The test has been copied over from upstream to make it
    // easier to understand the compatibility test coverage - not sure why the gRPC test doesn't fail but it
    // doesn't seem worth investigating too hard on this one.
    @Ignore
    @Test(timeout = 10000)
    public void deadlineInPast() throws Exception {
        // Test once with idle channel and once with active channel
        TestServiceGrpc.TestServiceBlockingStub stub =
                Clients.newDerivedClient(
                        blockingStub,

                        ClientOption.DEFAULT_RESPONSE_TIMEOUT_MILLIS.newValue(TimeUnit.SECONDS.toMillis(-10)));
        stub.emptyCall(EMPTY);
        Throwable t = catchThrowable(() -> stub.emptyCall(EMPTY));
        assertThat(t).isInstanceOf(StatusRuntimeException.class);
        assertThat(((StatusRuntimeException) t).getStatus().getCode());

        // warm up the channel
        blockingStub.emptyCall(Empty.getDefaultInstance());
        t = catchThrowable(() -> stub.emptyCall(EMPTY));
        assertThat(t).isInstanceOf(StatusRuntimeException.class);
        assertThat(((StatusRuntimeException) t).getStatus().getCode());
    }

    @Test(timeout = 10000)
    public void maxInboundSize_exact() {
        StreamingOutputCallRequest request =
                StreamingOutputCallRequest.newBuilder()
                                          .addResponseParameters(ResponseParameters.newBuilder().setSize(1))
                                          .build();
        int size = blockingStub.streamingOutputCall(request).next().getSerializedSize();

        TestServiceBlockingStub stub =
                Clients.newDerivedClient(
                        blockingStub,
                        GrpcClientOptions.MAX_INBOUND_MESSAGE_SIZE_BYTES.newValue(size));
        stub.streamingOutputCall(request).next();
    }

    @Test(timeout = 10000)
    public void maxInboundSize_tooBig() {
        StreamingOutputCallRequest request =
                StreamingOutputCallRequest.newBuilder()
                                          .addResponseParameters(ResponseParameters.newBuilder().setSize(1))
                                          .build();
        int size = blockingStub.streamingOutputCall(request).next().getSerializedSize();

        TestServiceBlockingStub stub =
                Clients.newDerivedClient(
                        blockingStub,
                        GrpcClientOptions.MAX_INBOUND_MESSAGE_SIZE_BYTES.newValue(size - 1));
        Throwable t = catchThrowable(() -> stub.streamingOutputCall(request).next());
        assertThat(t).isInstanceOf(StatusRuntimeException.class);
        assertThat(((StatusRuntimeException) t).getStatus().getCode()).isEqualTo(Code.RESOURCE_EXHAUSTED);
        assertThat(Throwables.getStackTraceAsString(t)).contains("exceeds maximum");
    }

    @Test(timeout = 10000)
    public void maxOutboundSize_exact() {
        // set at least one field to ensure the size is non-zero.
        StreamingOutputCallRequest request =
                StreamingOutputCallRequest.newBuilder()
                                          .addResponseParameters(ResponseParameters.newBuilder().setSize(1))
                                          .build();
        TestServiceBlockingStub stub =
                Clients.newDerivedClient(
                        blockingStub,
                        GrpcClientOptions.MAX_OUTBOUND_MESSAGE_SIZE_BYTES.newValue(
                                request.getSerializedSize()));
        stub.streamingOutputCall(request).next();
    }

    @Test(timeout = 10000)
    public void maxOutboundSize_tooBig() {
        // set at least one field to ensure the size is non-zero.
        StreamingOutputCallRequest request =
                StreamingOutputCallRequest.newBuilder()
                                          .addResponseParameters(ResponseParameters.newBuilder().setSize(1))
                                          .build();
        TestServiceBlockingStub stub =
                Clients.newDerivedClient(
                        blockingStub,
                        GrpcClientOptions.MAX_OUTBOUND_MESSAGE_SIZE_BYTES.newValue(
                                request.getSerializedSize() - 1));
        Throwable t = catchThrowable(() -> stub.streamingOutputCall(request).next());
        assertThat(t).isInstanceOf(StatusRuntimeException.class);
        assertThat(((StatusRuntimeException) t).getStatus().getCode()).isEqualTo(Code.CANCELLED);
        assertThat(Throwables.getStackTraceAsString(t)).contains("message too large");
    }

    @Test(timeout = 10000)
    public void statusCodeAndMessage() throws Exception {
        int errorCode = 2;
        String errorMessage = "test status message";
        EchoStatus responseStatus = EchoStatus.newBuilder()
                                              .setCode(errorCode)
                                              .setMessage(errorMessage)
                                              .build();
        SimpleRequest simpleRequest = SimpleRequest.newBuilder()
                                                   .setResponseStatus(responseStatus)
                                                   .build();
        StreamingOutputCallRequest streamingRequest = StreamingOutputCallRequest.newBuilder()
                                                                                .setResponseStatus(
                                                                                        responseStatus)
                                                                                .build();

        // Test UnaryCall
        Throwable t = catchThrowable(() -> blockingStub.unaryCall(simpleRequest));
        assertThat(t).isInstanceOf(StatusRuntimeException.class);
        StatusRuntimeException e = (StatusRuntimeException) t;
        assertThat(e.getStatus().getCode()).isEqualTo(Status.UNKNOWN.getCode());
        assertThat(e.getStatus().getDescription()).isEqualTo(errorMessage);

        // Test FullDuplexCall
        @SuppressWarnings("unchecked")
        StreamObserver<StreamingOutputCallResponse> responseObserver =
                mock(StreamObserver.class);
        StreamObserver<StreamingOutputCallRequest> requestObserver
                = asyncStub.fullDuplexCall(responseObserver);
        requestObserver.onNext(streamingRequest);
        requestObserver.onCompleted();

        ArgumentCaptor<Throwable> captor = ArgumentCaptor.forClass(Throwable.class);
        verify(responseObserver, timeout(operationTimeoutMillis())).onError(captor.capture());
        assertThat(Status.fromThrowable(captor.getValue()).getCode()).isEqualTo(Status.UNKNOWN.getCode());
        assertThat(Status.fromThrowable(captor.getValue()).getDescription()).isEqualTo(errorMessage);
        verifyNoMoreInteractions(responseObserver);
    }

    /** Sends an rpc to an unimplemented method within TestService. */
    @Test(timeout = 10000)
    public void unimplementedMethod() {
        Throwable t = catchThrowable(() -> blockingStub.unimplementedCall(Empty.getDefaultInstance()));
        assertThat(t).isInstanceOf(StatusRuntimeException.class);
        assertThat(((StatusRuntimeException) t).getStatus().getCode())
                .isEqualTo(Status.UNIMPLEMENTED.getCode());
    }

    @Test(timeout = 10000)
    public void unimplementedService() {
        UnimplementedServiceGrpc.UnimplementedServiceBlockingStub stub =
                UnimplementedServiceGrpc.newBlockingStub(asyncStub.getChannel());
        Throwable t = catchThrowable(() -> stub.unimplementedCall(Empty.getDefaultInstance()));
        assertThat(t).isInstanceOf(StatusRuntimeException.class);
        assertThat(((StatusRuntimeException) t).getStatus().getCode())
                .isEqualTo(Status.UNIMPLEMENTED.getCode());
    }

    /** Start a fullDuplexCall which the server will not respond, and verify the deadline expires. */
    @Test(timeout = 10000)
    public void timeoutOnSleepingServer() throws Exception {
        TestServiceStub stub = Clients.newDerivedClient(
                asyncStub,
                ClientOption.DEFAULT_RESPONSE_TIMEOUT_MILLIS.newValue(1L));

        StreamRecorder<StreamingOutputCallResponse> responseObserver = StreamRecorder.create();
        StreamObserver<StreamingOutputCallRequest> requestObserver
                = stub.fullDuplexCall(responseObserver);

        StreamingOutputCallRequest request =
                StreamingOutputCallRequest
                        .newBuilder()
                        .setPayload(Payload.newBuilder()
                                           .setBody(ByteString.copyFrom(new byte[27182])))
                        .addResponseParameters(
                                ResponseParameters.newBuilder()
                                                  .setIntervalUs((int) TimeUnit.SECONDS.toMicros(10)))
                        .build();
        try {
            requestObserver.onNext(request);
            // TODO(anuraag): Upstream test does not need to call onCompleted - response timeout is set at the
            // start of the stream, which seems to make sense. Figure out how to fix this timeout handling in
            // armeria.
            requestObserver.onCompleted();
        } catch (IllegalStateException expected) {
            // This can happen if the stream has already been terminated due to deadline exceeded.
        }

        responseObserver.awaitCompletion(operationTimeoutMillis(), TimeUnit.MILLISECONDS);
        assertThat(responseObserver.getValues()).isEmpty();
        assertThat(responseObserver.getError()).isNotNull();
        // TODO(anuraag): As gRPC supports handling timeouts in the server or client due to the grpc-timeout
        // header, it's not guaranteed which is the source of this error.
        // Until https://github.com/line/armeria/issues/521 a server side timeout will not have the correct
        // status so we don't verify it for now.
        //assertThat(Status.fromThrowable(responseObserver.getError()).getCode())
        //.isEqualTo(Status.DEADLINE_EXCEEDED.getCode());
    }

    private static void assertSuccess(StreamRecorder<?> recorder) {
        if (recorder.getError() != null) {
            throw new AssertionError(recorder.getError());
        }
    }

    private static int unaryPayloadLength() {
        // 10MiB.
        return 10485760;
    }

    private static int operationTimeoutMillis() {
        return 5000;
    }
}
