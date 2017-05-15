/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.server.grpc;

import static com.linecorp.armeria.common.http.HttpSessionProtocols.HTTP;
import static com.linecorp.armeria.internal.grpc.GrpcTestUtil.REQUEST_MESSAGE;
import static com.linecorp.armeria.internal.grpc.GrpcTestUtil.RESPONSE_MESSAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

import com.google.common.base.Strings;
import com.google.common.primitives.Bytes;
import com.google.common.primitives.Ints;
import com.google.protobuf.ByteString;

import com.linecorp.armeria.client.http.HttpClient;
import com.linecorp.armeria.client.http.HttpClientFactory;
import com.linecorp.armeria.common.http.AggregatedHttpMessage;
import com.linecorp.armeria.common.http.HttpHeaderNames;
import com.linecorp.armeria.common.http.HttpHeaders;
import com.linecorp.armeria.common.http.HttpMethod;
import com.linecorp.armeria.common.http.HttpStatus;
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

public class GrpcServiceServerTest {

    private static final int MAX_MESSAGE_SIZE = 16 * 1024 * 1024;

    private static final AsciiString LARGE_PAYLOAD = AsciiString.of(Strings.repeat("a", MAX_MESSAGE_SIZE + 1));

    private static class UnitTestServiceImpl extends UnitTestServiceImplBase {

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
    }

    @ClassRule
    public static ServerRule server = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.numWorkers(1);
            sb.port(0, HTTP);

            sb.serviceUnder("/", new GrpcServiceBuilder()
                    .setMaxInboundMessageSizeBytes(MAX_MESSAGE_SIZE)
                    .addService(new UnitTestServiceImpl())
                    .enableUnframedRequests(true)
                    .build());
        }
    };

    @Rule
    public Timeout globalTimeout = new Timeout(10, TimeUnit.SECONDS);

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
        assertThat(t.getStatus().getCode()).isEqualTo(Code.RESOURCE_EXHAUSTED);
        assertThat(t.getMessage()).contains("Frame size 16777227 exceeds maximum: 16777216");
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
        assertThat(t.getStatus().getCode()).isEqualTo(Code.INTERNAL);
        assertThat(t.getMessage())
                .contains("Compressed frame exceeds maximum frame size: 16777216. Bytes read: 16777227.");
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
        HttpClient client = HttpClientFactory.DEFAULT
                .newClient("none+" + server.httpUri("/"),
                           HttpClient.class);
        AggregatedHttpMessage response = client.execute(
                HttpHeaders.of(HttpMethod.POST,
                               UnitTestServiceGrpc.METHOD_STATIC_UNARY_CALL.getFullMethodName())
                           .set(HttpHeaderNames.CONTENT_TYPE, "application/protobuf"),
                REQUEST_MESSAGE.toByteArray()).aggregate().get();
        SimpleResponse message = SimpleResponse.parseFrom(response.content().array());
        assertThat(message).isEqualTo(RESPONSE_MESSAGE);
    }

    @Test
    public void unframed_acceptEncoding() throws Exception {
        HttpClient client = HttpClientFactory.DEFAULT
                .newClient("none+" + server.httpUri("/"),
                           HttpClient.class);
        AggregatedHttpMessage response = client.execute(
                HttpHeaders.of(HttpMethod.POST,
                               UnitTestServiceGrpc.METHOD_STATIC_UNARY_CALL.getFullMethodName())
                           .set(HttpHeaderNames.CONTENT_TYPE, "application/protobuf")
                           .set(GrpcHeaderNames.GRPC_ACCEPT_ENCODING, "gzip,none"),
                REQUEST_MESSAGE.toByteArray()).aggregate().get();
        SimpleResponse message = SimpleResponse.parseFrom(response.content().array());
        assertThat(message).isEqualTo(RESPONSE_MESSAGE);
    }

    @Test
    public void unframed_streamingApi() throws Exception {
        HttpClient client = HttpClientFactory.DEFAULT
                .newClient("none+" + server.httpUri("/"),
                           HttpClient.class);
        AggregatedHttpMessage response = client.execute(
                HttpHeaders.of(HttpMethod.POST,
                               UnitTestServiceGrpc.METHOD_STATIC_STREAMED_OUTPUT_CALL.getFullMethodName())
                           .set(HttpHeaderNames.CONTENT_TYPE, "application/protobuf"),
                StreamingOutputCallRequest.getDefaultInstance().toByteArray()).aggregate().get();
        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    public void unframed_noContentType() throws Exception {
        HttpClient client = HttpClientFactory.DEFAULT
                .newClient("none+" + server.httpUri("/"),
                           HttpClient.class);
        AggregatedHttpMessage response = client.execute(
                HttpHeaders.of(HttpMethod.POST,
                               UnitTestServiceGrpc.METHOD_STATIC_UNARY_CALL.getFullMethodName()),
                REQUEST_MESSAGE.toByteArray()).aggregate().get();
        assertThat(response.status()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    public void unframed_grpcEncoding() throws Exception {
        HttpClient client = HttpClientFactory.DEFAULT
                .newClient("none+" + server.httpUri("/"),
                           HttpClient.class);
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
        HttpClient client = HttpClientFactory.DEFAULT
                .newClient("none+" + server.httpUri("/"),
                           HttpClient.class);
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
        HttpClient client = HttpClientFactory.DEFAULT
                .newClient("none+" + server.httpUri("/"),
                           HttpClient.class);
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
                                GrpcTestUtil.protoByteBuf(GrpcTestUtil.RESPONSE_MESSAGE)),
                        serializedTrailers));
    }
}
