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
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.ClassRule;
import org.junit.Test;

import com.linecorp.armeria.client.http.HttpClient;
import com.linecorp.armeria.client.http.HttpClientFactory;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.common.http.AggregatedHttpMessage;
import com.linecorp.armeria.common.http.HttpHeaderNames;
import com.linecorp.armeria.common.http.HttpHeaders;
import com.linecorp.armeria.common.http.HttpMethod;
import com.linecorp.armeria.common.http.HttpStatus;
import com.linecorp.armeria.grpc.testing.Messages.EchoStatus;
import com.linecorp.armeria.grpc.testing.Messages.SimpleRequest;
import com.linecorp.armeria.grpc.testing.Messages.SimpleResponse;
import com.linecorp.armeria.grpc.testing.Messages.StreamingOutputCallRequest;
import com.linecorp.armeria.grpc.testing.Messages.StreamingOutputCallResponse;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc.TestServiceImplBase;
import com.linecorp.armeria.internal.grpc.GrpcHeaderNames;
import com.linecorp.armeria.internal.grpc.GrpcTestUtil;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.server.ServerRule;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

public class GrpcServiceServerTest {

    private static class TestServiceImpl extends TestServiceImplBase {

        @Override
        public void unaryCall(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            if (request.hasResponseStatus()) {
                throw new StatusRuntimeException(Status.fromCodeValue(request.getResponseStatus().getCode()));
            }
            responseObserver.onNext(GrpcTestUtil.RESPONSE_MESSAGE);
            responseObserver.onCompleted();
        }

        @Override
        public void streamingOutputCall(StreamingOutputCallRequest request,
                                        StreamObserver<StreamingOutputCallResponse> responseObserver) {
            responseObserver.onNext(
                    StreamingOutputCallResponse.newBuilder()
                                               .setPayload(request.getPayload())
                                               .build());
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
                    .addService(new TestServiceImpl())
                    .enableUnframedRequests(true)
                    .build());
        }
    };

    @Test
    public void unframed() throws Exception {
        HttpClient client = HttpClientFactory.DEFAULT
                .newClient("none+" + server.httpUri("/"),
                           HttpClient.class);
        AggregatedHttpMessage response = client.execute(
                HttpHeaders.of(HttpMethod.POST, TestServiceGrpc.METHOD_UNARY_CALL.getFullMethodName())
                           .set(HttpHeaderNames.CONTENT_TYPE, "application/protobuf"),
                GrpcTestUtil.REQUEST_MESSAGE.toByteArray()).aggregate().get();
        SimpleResponse message = SimpleResponse.parseFrom(response.content().array());
        assertThat(message).isEqualTo(GrpcTestUtil.RESPONSE_MESSAGE);
    }

    @Test
    public void unframed_acceptEncoding() throws Exception {
        HttpClient client = HttpClientFactory.DEFAULT
                .newClient("none+" + server.httpUri("/"),
                           HttpClient.class);
        AggregatedHttpMessage response = client.execute(
                HttpHeaders.of(HttpMethod.POST, TestServiceGrpc.METHOD_UNARY_CALL.getFullMethodName())
                           .set(HttpHeaderNames.CONTENT_TYPE, "application/protobuf")
                           .set(GrpcHeaderNames.GRPC_ACCEPT_ENCODING, "gzip,none"),
                GrpcTestUtil.REQUEST_MESSAGE.toByteArray()).aggregate().get();
        SimpleResponse message = SimpleResponse.parseFrom(response.content().array());
        assertThat(message).isEqualTo(GrpcTestUtil.RESPONSE_MESSAGE);
    }

    @Test
    public void unframed_streamingApi() throws Exception {
        HttpClient client = HttpClientFactory.DEFAULT
                .newClient("none+" + server.httpUri("/"),
                           HttpClient.class);
        AggregatedHttpMessage response = client.execute(
                HttpHeaders.of(HttpMethod.POST,
                               TestServiceGrpc.METHOD_STREAMING_OUTPUT_CALL.getFullMethodName())
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
                               TestServiceGrpc.METHOD_UNARY_CALL.getFullMethodName()),
                GrpcTestUtil.REQUEST_MESSAGE.toByteArray()).aggregate().get();
        assertThat(response.status()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    public void unframed_grpcEncoding() throws Exception {
        HttpClient client = HttpClientFactory.DEFAULT
                .newClient("none+" + server.httpUri("/"),
                           HttpClient.class);
        AggregatedHttpMessage response = client.execute(
                HttpHeaders.of(HttpMethod.POST,
                               TestServiceGrpc.METHOD_UNARY_CALL.getFullMethodName())
                           .set(HttpHeaderNames.CONTENT_TYPE, "application/protobuf")
                           .set(GrpcHeaderNames.GRPC_ENCODING, "gzip"),
                GrpcTestUtil.REQUEST_MESSAGE.toByteArray()).aggregate().get();
        assertThat(response.status()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    public void unframed_serviceError() throws Exception {
        HttpClient client = HttpClientFactory.DEFAULT
                .newClient("none+" + server.httpUri("/"),
                           HttpClient.class);
        AggregatedHttpMessage response = client.execute(
                HttpHeaders.of(HttpMethod.POST,
                               TestServiceGrpc.METHOD_UNARY_CALL.getFullMethodName())
                           .set(HttpHeaderNames.CONTENT_TYPE, "application/protobuf"),
                SimpleRequest.newBuilder()
                             .setResponseStatus(
                                     EchoStatus.newBuilder()
                                               .setCode(Status.DEADLINE_EXCEEDED.getCode().value()))
                             .build().toByteArray()).aggregate().get();
        assertThat(response.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    // TODO(anuraag): Replace with an actual grpc client after armeria supports one.
    @Test
    public void framed() throws Exception {
        HttpClient client = HttpClientFactory.DEFAULT
                .newClient("none+" + server.httpUri("/"),
                           HttpClient.class);
        AggregatedHttpMessage response = client.execute(
                HttpHeaders.of(HttpMethod.POST,
                               TestServiceGrpc.METHOD_UNARY_CALL.getFullMethodName())
                           .set(HttpHeaderNames.CONTENT_TYPE,
                                GrpcSerializationFormats.PROTO.mediaType().toString()),
                GrpcTestUtil.uncompressedFrame(GrpcTestUtil.requestByteBuf())).aggregate().get();
        assertThat(response.content().array())
                .containsExactly(GrpcTestUtil.uncompressedResponseBytes());
    }

}
