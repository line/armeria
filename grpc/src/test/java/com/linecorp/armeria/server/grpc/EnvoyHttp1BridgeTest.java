/*
 * Copyright 2026 LINE Corporation
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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import testing.grpc.EmptyProtos.Empty;
import testing.grpc.Messages.SimpleRequest;
import testing.grpc.Messages.SimpleResponse;
import testing.grpc.Messages.StreamingOutputCallRequest;
import testing.grpc.Messages.StreamingOutputCallResponse;
import testing.grpc.TestServiceGrpc;
import testing.grpc.TestServiceGrpc.TestServiceBlockingStub;
import testing.grpc.TestServiceGrpc.TestServiceImplBase;

class EnvoyHttp1BridgeTest {

    // gRPC frame for an Empty protobuf message:
    // byte[0] = 0 (no compression), byte[1..4] = 0 (payload length = 0)
    private static final byte[] EMPTY_GRPC_FRAME = {0, 0, 0, 0, 0};

    private static class SuccessService extends TestServiceImplBase {
        @Override
        public void emptyCall(Empty request, StreamObserver<Empty> responseObserver) {
            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
        }

        @Override
        public void unaryCall2(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            responseObserver.onNext(SimpleResponse.getDefaultInstance());
            responseObserver.onCompleted();
        }

        @Override
        public void streamingOutputCall(StreamingOutputCallRequest request,
                                        StreamObserver<StreamingOutputCallResponse> responseObserver) {
            // Send at least one message to avoid a Trailers-Only response,
            // which would place grpc-status in the initial headers rather than trailers.
            responseObserver.onNext(StreamingOutputCallResponse.getDefaultInstance());
            responseObserver.onCompleted();
        }
    }

    private static class ErrorService extends TestServiceImplBase {
        @Override
        public void emptyCall(Empty request, StreamObserver<Empty> responseObserver) {
            responseObserver.onError(
                    Status.INTERNAL.withDescription("test error").asRuntimeException());
        }
    }

    @RegisterExtension
    static ServerExtension bridgeServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service(GrpcService.builder()
                                  .addService(new SuccessService())
                                  .enableEnvoyHttp1Bridge(true)
                                  .build());
        }
    };

    @RegisterExtension
    static ServerExtension bridgeErrorServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service(GrpcService.builder()
                                  .addService(new ErrorService())
                                  .enableEnvoyHttp1Bridge(true)
                                  .build());
        }
    };

    @RegisterExtension
    static ServerExtension noBridgeServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service(GrpcService.builder()
                                  .addService(new SuccessService())
                                  .build());
        }
    };

    @RegisterExtension
    static ServerExtension transcodingBridgeServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service(GrpcService.builder()
                                  .addService(new SuccessService())
                                  .enableEnvoyHttp1Bridge(true)
                                  .enableHttpJsonTranscoding(true)
                                  .supportedSerializationFormats(
                                          GrpcSerializationFormats.PROTO, GrpcSerializationFormats.JSON)
                                  .build());
        }
    };

    @RegisterExtension
    static ServerExtension transcodingNoBridgeServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service(GrpcService.builder()
                                  .addService(new SuccessService())
                                  .enableHttpJsonTranscoding(true)
                                  .supportedSerializationFormats(
                                          GrpcSerializationFormats.PROTO, GrpcSerializationFormats.JSON)
                                  .build());
        }
    };

    private static BlockingWebClient h1cClient(ServerExtension server) {
        return WebClient.of(server.uri(SessionProtocol.H1C)).blocking();
    }

    private static BlockingWebClient h2cClient(ServerExtension server) {
        return WebClient.of(server.uri(SessionProtocol.H2C)).blocking();
    }

    @Test
    void trailersAreMergedIntoHeadersForUnaryOverHttp1() {
        final BlockingWebClient client = h1cClient(bridgeServer);
        final AggregatedHttpResponse response =
                client.prepare()
                      .post(TestServiceGrpc.getEmptyCallMethod().getFullMethodName())
                      .content(GrpcSerializationFormats.PROTO.mediaType(), EMPTY_GRPC_FRAME)
                      .execute();

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        // With bridge, grpc-status must be present in response headers
        assertThat(response.headers().get(GrpcHeaderNames.GRPC_STATUS)).isEqualTo("0");
        // Trailers must be empty since they were merged into headers
        assertThat(response.trailers()).isEmpty();
    }

    @Test
    void grpcStatusInHeadersOnErrorOverHttp1() {
        final BlockingWebClient client = h1cClient(bridgeErrorServer);
        final AggregatedHttpResponse response =
                client.prepare()
                      .post(TestServiceGrpc.getEmptyCallMethod().getFullMethodName())
                      .content(GrpcSerializationFormats.PROTO.mediaType(), EMPTY_GRPC_FRAME)
                      .execute();

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        // grpc-status 13 = INTERNAL
        assertThat(response.headers().get(GrpcHeaderNames.GRPC_STATUS)).isEqualTo("13");
        assertThat(response.headers().get(GrpcHeaderNames.GRPC_MESSAGE)).isEqualTo("test error");
        assertThat(response.trailers()).isEmpty();
    }

    @Test
    void bridgeNotAppliedForStreamingMethod() {
        final BlockingWebClient client = h1cClient(bridgeServer);
        final AggregatedHttpResponse response =
                client.prepare()
                      .post(TestServiceGrpc.getStreamingOutputCallMethod().getFullMethodName())
                      .content(GrpcSerializationFormats.PROTO.mediaType(), EMPTY_GRPC_FRAME)
                      .execute();

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        // Bridge must not be applied for streaming methods; grpc-status stays in trailers
        assertThat(response.headers().get(GrpcHeaderNames.GRPC_STATUS)).isNull();
        assertThat(response.trailers().get(GrpcHeaderNames.GRPC_STATUS)).isEqualTo("0");
    }

    @Test
    void bridgeNotAppliedWhenDisabled() {
        final BlockingWebClient client = h1cClient(noBridgeServer);
        final AggregatedHttpResponse response =
                client.prepare()
                      .post(TestServiceGrpc.getEmptyCallMethod().getFullMethodName())
                      .content(GrpcSerializationFormats.PROTO.mediaType(), EMPTY_GRPC_FRAME)
                      .execute();

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        // Without bridge, grpc-status must be in trailers, not in response headers
        assertThat(response.headers().get(GrpcHeaderNames.GRPC_STATUS)).isNull();
        assertThat(response.trailers().get(GrpcHeaderNames.GRPC_STATUS)).isEqualTo("0");
    }

    @Test
    void bridgeNotAppliedOverHttp2() {
        final BlockingWebClient client = h2cClient(bridgeServer);
        final AggregatedHttpResponse response =
                client.prepare()
                      .post(TestServiceGrpc.getEmptyCallMethod().getFullMethodName())
                      .content(GrpcSerializationFormats.PROTO.mediaType(), EMPTY_GRPC_FRAME)
                      .execute();

        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        // Bridge must not apply over HTTP/2; grpc-status must remain in trailers
        assertThat(response.headers().get(GrpcHeaderNames.GRPC_STATUS)).isNull();
        assertThat(response.trailers().get(GrpcHeaderNames.GRPC_STATUS)).isEqualTo("0");
    }

    @Test
    void jsonTranscodingNotAffectedByBridge() {
        // Compare bridge vs no-bridge on the same JSON transcoded endpoint
        final AggregatedHttpResponse withBridge =
                h1cClient(transcodingBridgeServer).prepare()
                                                  .post("/v1/unary-call-2")
                                                  .content(MediaType.JSON_UTF_8, "{}")
                                                  .execute();
        final AggregatedHttpResponse withoutBridge =
                h1cClient(transcodingNoBridgeServer).prepare()
                                                    .post("/v1/unary-call-2")
                                                    .content(MediaType.JSON_UTF_8, "{}")
                                                    .execute();

        assertThat(withBridge.status()).isEqualTo(HttpStatus.OK);
        assertThat(withBridge.contentType()).isEqualTo(MediaType.JSON_UTF_8);
        // Bridge must not change the transcoded response compared to no-bridge
        assertThat(withBridge.headers().get(GrpcHeaderNames.GRPC_STATUS))
                .isEqualTo(withoutBridge.headers().get(GrpcHeaderNames.GRPC_STATUS));
        assertThat(withBridge.contentUtf8()).isEqualTo(withoutBridge.contentUtf8());
    }

    @Test
    void armeriaGrpcClientCompatibleWithBridge() {
        final TestServiceBlockingStub client =
                GrpcClients.builder(bridgeServer.uri(SessionProtocol.H1C, GrpcSerializationFormats.PROTO))
                           .build(TestServiceBlockingStub.class);
        // Armeria gRPC client over HTTP/1.1 with bridge enabled should complete without error
        final Empty response = client.emptyCall(Empty.getDefaultInstance());
        assertThat(response).isEqualTo(Empty.getDefaultInstance());
    }
}
