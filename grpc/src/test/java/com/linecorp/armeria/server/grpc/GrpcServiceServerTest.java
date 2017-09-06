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

package com.linecorp.armeria.server.grpc;

import static com.linecorp.armeria.common.SessionProtocol.HTTP;
import static com.linecorp.armeria.internal.grpc.GrpcTestUtil.REQUEST_MESSAGE;
import static com.linecorp.armeria.internal.grpc.GrpcTestUtil.RESPONSE_MESSAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.DisableOnDebug;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;

import com.google.common.base.Strings;
import com.google.common.primitives.Bytes;
import com.google.common.primitives.Ints;
import com.google.protobuf.ByteString;

import com.linecorp.armeria.client.ClientBuilder;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.SimpleDecoratingClient;
import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.common.util.EventLoopGroups;
import com.linecorp.armeria.grpc.testing.Messages.EchoStatus;
import com.linecorp.armeria.grpc.testing.Messages.Payload;
import com.linecorp.armeria.grpc.testing.Messages.SimpleRequest;
import com.linecorp.armeria.grpc.testing.Messages.SimpleResponse;
import com.linecorp.armeria.grpc.testing.Messages.StreamingOutputCallRequest;
import com.linecorp.armeria.grpc.testing.UnitTestServiceGrpc;
import com.linecorp.armeria.grpc.testing.UnitTestServiceGrpc.UnitTestServiceBlockingStub;
import com.linecorp.armeria.grpc.testing.UnitTestServiceGrpc.UnitTestServiceImplBase;
import com.linecorp.armeria.grpc.testing.UnitTestServiceGrpc.UnitTestServiceStub;
import com.linecorp.armeria.internal.grpc.GrpcHeaderNames;
import com.linecorp.armeria.internal.grpc.GrpcTestUtil;
import com.linecorp.armeria.internal.grpc.StreamRecorder;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.server.ServerRule;

import io.grpc.Codec;
import io.grpc.DecompressorRegistry;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import io.netty.util.AsciiString;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;

public class GrpcServiceServerTest {

    private static final int MAX_MESSAGE_SIZE = 16 * 1024 * 1024;

    private static final AsciiString LARGE_PAYLOAD = AsciiString.of(Strings.repeat("a", MAX_MESSAGE_SIZE + 1));

    private static class UnitTestServiceImpl extends UnitTestServiceImplBase {

        private static final AttributeKey<Integer> CHECK_REQUEST_CONTEXT_COUNT =
                AttributeKey.valueOf(UnitTestServiceImpl.class, "CHECK_REQUEST_CONTEXT_COUNT");

        @Override
        public void staticUnaryCall(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            if (!request.equals(REQUEST_MESSAGE)) {
                responseObserver.onError(new IllegalArgumentException("Unexpected request: " + request));
                return;
            }
            responseObserver.onNext(RESPONSE_MESSAGE);
            responseObserver.onCompleted();
        }

        @Override
        public void staticStreamedOutputCall(SimpleRequest request,
                                             StreamObserver<SimpleResponse> responseObserver) {
            if (!request.equals(REQUEST_MESSAGE)) {
                responseObserver.onError(new IllegalArgumentException("Unexpected request: " + request));
                return;
            }
            responseObserver.onNext(RESPONSE_MESSAGE);
            responseObserver.onNext(RESPONSE_MESSAGE);
            responseObserver.onCompleted();
        }

        @Override
        public void errorNoMessage(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            responseObserver.onError(Status.ABORTED.asException());
        }

        @Override
        public void errorWithMessage(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            responseObserver.onError(Status.ABORTED.withDescription("aborted call").asException());
        }

        @Override
        public void unaryThrowsError(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            throw Status.ABORTED.withDescription("call aborted").asRuntimeException();
        }

        @Override
        public StreamObserver<SimpleRequest> streamThrowsError(
                StreamObserver<SimpleResponse> responseObserver) {
            return new StreamObserver<SimpleRequest>() {
                @Override
                public void onNext(SimpleRequest value) {
                    throw Status.ABORTED.withDescription("bad streaming message").asRuntimeException();
                }

                @Override
                public void onError(Throwable t) {}

                @Override
                public void onCompleted() {}
            };
        }

        @Override
        public StreamObserver<SimpleRequest> streamThrowsErrorInStub(
                StreamObserver<SimpleResponse> responseObserver) {
            throw Status.ABORTED.withDescription("bad streaming stub").asRuntimeException();
        }

        @Override
        public void staticUnaryCallSetsMessageCompression(SimpleRequest request,
                                                          StreamObserver<SimpleResponse> responseObserver) {
            if (!request.equals(REQUEST_MESSAGE)) {
                responseObserver.onError(new IllegalArgumentException("Unexpected request: " + request));
                return;
            }
            ServerCallStreamObserver<SimpleResponse> callObserver =
                    (ServerCallStreamObserver<SimpleResponse>) responseObserver;
            callObserver.setCompression("gzip");
            callObserver.setMessageCompression(true);
            responseObserver.onNext(RESPONSE_MESSAGE);
            responseObserver.onCompleted();
        }

        @Override
        public StreamObserver<SimpleRequest> checkRequestContext(
                StreamObserver<SimpleResponse> responseObserver) {
            RequestContext ctx = RequestContext.current();
            ctx.attr(CHECK_REQUEST_CONTEXT_COUNT).set(0);
            return new StreamObserver<SimpleRequest>() {
                @Override
                public void onNext(SimpleRequest value) {
                    RequestContext ctx = RequestContext.current();
                    Attribute<Integer> attr = ctx.attr(CHECK_REQUEST_CONTEXT_COUNT);
                    attr.set(attr.get() + 1);
                }

                @Override
                public void onError(Throwable t) {}

                @Override
                public void onCompleted() {
                    RequestContext ctx = RequestContext.current();
                    int count = ctx.attr(CHECK_REQUEST_CONTEXT_COUNT).get();
                    responseObserver.onNext(
                            SimpleResponse.newBuilder()
                                          .setPayload(
                                                  Payload.newBuilder()
                                                         .setBody(
                                                                 ByteString.copyFromUtf8(
                                                                         Integer.toString(count))))
                                          .build());
                    responseObserver.onCompleted();
                }
            };
        }
    }

    @ClassRule
    public static ServerRule server = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.workerGroup(EventLoopGroups.newEventLoopGroup(1), true);
            sb.port(0, HTTP);
            sb.defaultMaxRequestLength(0);

            sb.serviceUnder("/", new GrpcServiceBuilder()
                    .setMaxInboundMessageSizeBytes(MAX_MESSAGE_SIZE)
                    .addService(new UnitTestServiceImpl())
                    .enableUnframedRequests(true)
                    .supportedSerializationFormats(GrpcSerializationFormats.values())
                    .build());
        }
    };

    @Rule
    public TestRule globalTimeout = new DisableOnDebug(new Timeout(10, TimeUnit.SECONDS));

    private static ManagedChannel channel;

    private UnitTestServiceBlockingStub blockingClient;
    private UnitTestServiceStub streamingClient;

    @BeforeClass
    public static void setUpChannel() {
        channel = ManagedChannelBuilder.forAddress("127.0.0.1", server.httpPort())
                                       .usePlaintext(true)
                                       .build();
    }

    @AfterClass
    public static void tearDownChannel() {
        channel.shutdownNow();
    }

    @Before
    public void setUp() {
        blockingClient = UnitTestServiceGrpc.newBlockingStub(channel);
        streamingClient = UnitTestServiceGrpc.newStub(channel);
    }

    @Test
    public void unary_normal() throws Exception {
        assertThat(blockingClient.staticUnaryCall(REQUEST_MESSAGE)).isEqualTo(RESPONSE_MESSAGE);
    }

    @Test
    public void streamedOutput_normal() throws Exception {
        StreamRecorder<SimpleResponse> recorder = StreamRecorder.create();
        streamingClient.staticStreamedOutputCall(REQUEST_MESSAGE, recorder);
        recorder.awaitCompletion();
        assertThat(recorder.getValues()).containsExactly(RESPONSE_MESSAGE, RESPONSE_MESSAGE);
    }

    @Test
    public void error_noMessage() throws Exception {
        StatusRuntimeException t = (StatusRuntimeException) catchThrowable(
                () -> blockingClient.errorNoMessage(REQUEST_MESSAGE));
        assertThat(t.getStatus().getCode()).isEqualTo(Code.ABORTED);
        assertThat(t.getStatus().getDescription()).isNull();
    }

    @Test
    public void error_withMessage() throws Exception {
        StatusRuntimeException t = (StatusRuntimeException) catchThrowable(
                () -> blockingClient.errorWithMessage(REQUEST_MESSAGE));
        assertThat(t.getStatus().getCode()).isEqualTo(Code.ABORTED);
        assertThat(t.getStatus().getDescription()).isEqualTo("aborted call");
    }

    @Test
    public void error_thrown_unary() throws Exception {
        StatusRuntimeException t = (StatusRuntimeException) catchThrowable(
                () -> blockingClient.unaryThrowsError(REQUEST_MESSAGE));
        assertThat(t.getStatus().getCode()).isEqualTo(Code.ABORTED);
        assertThat(t.getStatus().getDescription()).isEqualTo("call aborted");
    }

    @Test
    public void error_thrown_streamMessage() throws Exception {
        StreamRecorder<SimpleResponse> response = StreamRecorder.create();
        StreamObserver<SimpleRequest> request = streamingClient.streamThrowsError(response);
        request.onNext(REQUEST_MESSAGE);
        response.awaitCompletion();
        StatusRuntimeException t = (StatusRuntimeException) response.getError();
        assertThat(t.getStatus().getCode()).isEqualTo(Code.ABORTED);
        assertThat(t.getStatus().getDescription()).isEqualTo("bad streaming message");
    }

    @Test
    public void error_thrown_streamStub() throws Exception {
        StreamRecorder<SimpleResponse> response = StreamRecorder.create();
        streamingClient.streamThrowsErrorInStub(response);
        response.awaitCompletion();
        StatusRuntimeException t = (StatusRuntimeException) response.getError();
        assertThat(t.getStatus().getCode()).isEqualTo(Code.ABORTED);
        assertThat(t.getStatus().getDescription()).isEqualTo("bad streaming stub");
    }

    @Test
    public void requestContextSet() throws Exception {
        StreamRecorder<SimpleResponse> response = StreamRecorder.create();
        StreamObserver<SimpleRequest> request = streamingClient.checkRequestContext(response);
        request.onNext(REQUEST_MESSAGE);
        request.onNext(REQUEST_MESSAGE);
        request.onNext(REQUEST_MESSAGE);
        request.onCompleted();
        response.awaitCompletion();
        assertThat(response.getValues())
                .containsExactly(
                        SimpleResponse.newBuilder()
                                      .setPayload(Payload.newBuilder()
                                                         .setBody(ByteString.copyFromUtf8("3")))
                                      .build());
    }

    @Test
    public void tooLargeRequest_uncompressed() throws Exception {
        SimpleRequest request = SimpleRequest.newBuilder()
                                             .setPayload(
                                                     Payload.newBuilder()
                                                            .setBody(ByteString.copyFrom(
                                                                    LARGE_PAYLOAD.toByteArray())))
                                             .build();
        StatusRuntimeException t =
                (StatusRuntimeException) catchThrowable(
                        () -> blockingClient.staticUnaryCall(request));
        // NB: Since gRPC does not support HTTP/1, it just resets the stream with an HTTP/2 CANCEL error code,
        // which clients would interpret as Code.CANCELLED. Armeria supports HTTP/1, so more generically returns
        // an HTTP 500.
        assertThat(t.getStatus().getCode()).isEqualTo(Code.UNKNOWN);
    }

    @Test
    public void tooLargeRequest_compressed() throws Exception {
        SimpleRequest request = SimpleRequest.newBuilder()
                                             .setPayload(
                                                     Payload.newBuilder()
                                                            .setBody(ByteString.copyFrom(
                                                                    LARGE_PAYLOAD.toByteArray())))
                                             .build();
        StatusRuntimeException t =
                (StatusRuntimeException) catchThrowable(
                        () -> blockingClient.withCompression("gzip").staticUnaryCall(request));
        // NB: Since gRPC does not support HTTP/1, it just resets the stream with an HTTP/2 CANCEL error code,
        // which clients would interpret as Code.CANCELLED. Armeria supports HTTP/1, so more generically returns
        // an HTTP 500.
        assertThat(t.getStatus().getCode()).isEqualTo(Code.UNKNOWN);
    }

    @Test
    public void uncompressedClient_compressedEndpoint() throws Exception {
        ManagedChannel nonDecompressingChannel =
                ManagedChannelBuilder.forAddress("127.0.0.1", server.httpPort())
                                     .decompressorRegistry(
                                             DecompressorRegistry.emptyInstance()
                                                                 .with(Codec.Identity.NONE, false))
                                     .usePlaintext(true)
                                     .build();
        UnitTestServiceBlockingStub client = UnitTestServiceGrpc.newBlockingStub(nonDecompressingChannel);
        assertThat(client.staticUnaryCallSetsMessageCompression(REQUEST_MESSAGE))
                .isEqualTo(RESPONSE_MESSAGE);
        nonDecompressingChannel.shutdownNow();
    }

    @Test
    public void compressedClient_compressedEndpoint() throws Exception {
        assertThat(blockingClient.staticUnaryCallSetsMessageCompression(REQUEST_MESSAGE))
                .isEqualTo(RESPONSE_MESSAGE);
    }

    @Test
    public void unframed() throws Exception {
        HttpClient client = HttpClient.of(server.httpUri("/"));
        AggregatedHttpMessage response = client.execute(
                HttpHeaders.of(HttpMethod.POST,
                               UnitTestServiceGrpc.METHOD_STATIC_UNARY_CALL.getFullMethodName())
                           .set(HttpHeaderNames.CONTENT_TYPE, "application/protobuf"),
                REQUEST_MESSAGE.toByteArray()).aggregate().get();
        SimpleResponse message = SimpleResponse.parseFrom(response.content().array());
        assertThat(message).isEqualTo(RESPONSE_MESSAGE);
        assertThat(response.headers().getInt(HttpHeaderNames.CONTENT_LENGTH))
                .isEqualTo(response.content().length());
    }

    @Test
    public void unframed_acceptEncoding() throws Exception {
        HttpClient client = HttpClient.of(server.httpUri("/"));
        AggregatedHttpMessage response = client.execute(
                HttpHeaders.of(HttpMethod.POST,
                               UnitTestServiceGrpc.METHOD_STATIC_UNARY_CALL.getFullMethodName())
                           .set(HttpHeaderNames.CONTENT_TYPE, "application/protobuf")
                           .set(GrpcHeaderNames.GRPC_ACCEPT_ENCODING, "gzip,none"),
                REQUEST_MESSAGE.toByteArray()).aggregate().get();
        SimpleResponse message = SimpleResponse.parseFrom(response.content().array());
        assertThat(message).isEqualTo(RESPONSE_MESSAGE);
        assertThat(response.headers().getInt(HttpHeaderNames.CONTENT_LENGTH))
                .isEqualTo(response.content().length());
    }

    @Test
    public void unframed_streamingApi() throws Exception {
        HttpClient client = HttpClient.of(server.httpUri("/"));
        AggregatedHttpMessage response = client.execute(
                HttpHeaders.of(HttpMethod.POST,
                               UnitTestServiceGrpc.METHOD_STATIC_STREAMED_OUTPUT_CALL.getFullMethodName())
                           .set(HttpHeaderNames.CONTENT_TYPE, "application/protobuf"),
                StreamingOutputCallRequest.getDefaultInstance().toByteArray()).aggregate().get();
        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    public void unframed_noContentType() throws Exception {
        HttpClient client = HttpClient.of(server.httpUri("/"));
        AggregatedHttpMessage response = client.execute(
                HttpHeaders.of(HttpMethod.POST,
                               UnitTestServiceGrpc.METHOD_STATIC_UNARY_CALL.getFullMethodName()),
                REQUEST_MESSAGE.toByteArray()).aggregate().get();
        assertThat(response.status()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    public void unframed_grpcEncoding() throws Exception {
        HttpClient client = HttpClient.of(server.httpUri("/"));
        AggregatedHttpMessage response = client.execute(
                HttpHeaders.of(HttpMethod.POST,
                               UnitTestServiceGrpc.METHOD_STATIC_UNARY_CALL.getFullMethodName())
                           .set(HttpHeaderNames.CONTENT_TYPE, "application/protobuf")
                           .set(GrpcHeaderNames.GRPC_ENCODING, "gzip"),
                REQUEST_MESSAGE.toByteArray()).aggregate().get();
        assertThat(response.status()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    public void unframed_serviceError() throws Exception {
        HttpClient client = HttpClient.of(server.httpUri("/"));
        AggregatedHttpMessage response = client.execute(
                HttpHeaders.of(HttpMethod.POST,
                               UnitTestServiceGrpc.METHOD_STATIC_UNARY_CALL.getFullMethodName())
                           .set(HttpHeaderNames.CONTENT_TYPE, "application/protobuf"),
                SimpleRequest.newBuilder()
                             .setResponseStatus(
                                     EchoStatus.newBuilder()
                                               .setCode(Status.DEADLINE_EXCEEDED.getCode().value()))
                             .build().toByteArray()).aggregate().get();
        assertThat(response.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    public void grpcWeb() throws Exception {
        HttpClient client = HttpClient.of(server.httpUri("/"));
        AggregatedHttpMessage response = client.execute(
                HttpHeaders.of(HttpMethod.POST,
                               UnitTestServiceGrpc.METHOD_STATIC_UNARY_CALL.getFullMethodName())
                           .set(HttpHeaderNames.CONTENT_TYPE, "application/grpc-web"),
                GrpcTestUtil.uncompressedFrame(GrpcTestUtil.requestByteBuf())).aggregate().get();
        byte[] serializedStatusHeader = "grpc-status: 0\r\n".getBytes(StandardCharsets.US_ASCII);
        byte[] serializedTrailers = Bytes.concat(
                new byte[] { ArmeriaServerCall.TRAILERS_FRAME_HEADER },
                Ints.toByteArray(serializedStatusHeader.length),
                serializedStatusHeader);
        assertThat(response.content().array()).containsExactly(
                Bytes.concat(
                        GrpcTestUtil.uncompressedFrame(
                                GrpcTestUtil.protoByteBuf(RESPONSE_MESSAGE)),
                        serializedTrailers));
    }

    @Test
    public void json() throws Exception {
        AtomicReference<HttpHeaders> requestHeaders = new AtomicReference<>();
        UnitTestServiceBlockingStub jsonStub =
                new ClientBuilder(server.httpUri(GrpcSerializationFormats.JSON, "/"))
                        .decorator(HttpRequest.class, HttpResponse.class,
                                   client -> new SimpleDecoratingClient<HttpRequest, HttpResponse>(client) {
                                       @Override
                                       public HttpResponse execute(ClientRequestContext ctx, HttpRequest req)
                                               throws Exception {
                                           requestHeaders.set(req.headers());
                                           return delegate().execute(ctx, req);
                                       }
                                   })
                        .build(UnitTestServiceBlockingStub.class);
        SimpleResponse response = jsonStub.staticUnaryCall(REQUEST_MESSAGE);
        assertThat(response).isEqualTo(RESPONSE_MESSAGE);
        assertThat(requestHeaders.get().get(HttpHeaderNames.CONTENT_TYPE)).isEqualTo("application/grpc+json");
    }
}
