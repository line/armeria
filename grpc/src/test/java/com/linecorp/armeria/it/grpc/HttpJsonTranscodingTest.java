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
package com.linecorp.armeria.it.grpc;

import static com.linecorp.armeria.it.grpc.HttpJsonTranscodingTest.HttpJsonTranscodingTestService.testBytesValue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.withPrecision;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.QueryParamsBuilder;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.grpc.testing.HttpJsonTranscodingTestServiceGrpc.HttpJsonTranscodingTestServiceBlockingStub;
import com.linecorp.armeria.grpc.testing.HttpJsonTranscodingTestServiceGrpc.HttpJsonTranscodingTestServiceImplBase;
import com.linecorp.armeria.grpc.testing.Transcoding.EchoTimestampAndDurationRequest;
import com.linecorp.armeria.grpc.testing.Transcoding.EchoTimestampAndDurationResponse;
import com.linecorp.armeria.grpc.testing.Transcoding.EchoWrappersRequest;
import com.linecorp.armeria.grpc.testing.Transcoding.EchoWrappersResponse;
import com.linecorp.armeria.grpc.testing.Transcoding.GetMessageRequestV1;
import com.linecorp.armeria.grpc.testing.Transcoding.GetMessageRequestV2;
import com.linecorp.armeria.grpc.testing.Transcoding.GetMessageRequestV2.SubMessage;
import com.linecorp.armeria.grpc.testing.Transcoding.GetMessageRequestV3;
import com.linecorp.armeria.grpc.testing.Transcoding.Message;
import com.linecorp.armeria.grpc.testing.Transcoding.MessageType;
import com.linecorp.armeria.grpc.testing.Transcoding.UpdateMessageRequestV1;
import com.linecorp.armeria.internal.common.JacksonUtil;
import com.linecorp.armeria.internal.server.grpc.GrpcDocServicePlugin;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.docs.DocService;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.stub.StreamObserver;

class HttpJsonTranscodingTest {

    static class HttpJsonTranscodingTestService extends HttpJsonTranscodingTestServiceImplBase {

        static final String testBytesValue = "abc123!?$*&()'-=@~";

        @Override
        public void getMessageV1(GetMessageRequestV1 request, StreamObserver<Message> responseObserver) {
            responseObserver.onNext(Message.newBuilder().setText(request.getName()).build());
            responseObserver.onCompleted();
        }

        @Override
        public void getMessageV2(GetMessageRequestV2 request, StreamObserver<Message> responseObserver) {
            final String text = request.getMessageId() + ':' +
                                request.getRevision() + ':' +
                                request.getSub().getSubfield() + ':' +
                                request.getType();
            responseObserver.onNext(Message.newBuilder().setText(text).build());
            responseObserver.onCompleted();
        }

        @Override
        public void getMessageV3(GetMessageRequestV3 request, StreamObserver<Message> responseObserver) {
            final String text = request.getMessageId() + ':' +
                                request.getRevisionList().stream().map(String::valueOf)
                                       .collect(Collectors.joining(":"));
            responseObserver.onNext(Message.newBuilder().setText(text).build());
            responseObserver.onCompleted();
        }

        @Override
        public void updateMessageV1(UpdateMessageRequestV1 request, StreamObserver<Message> responseObserver) {
            final String text = request.getMessageId() + ':' +
                                request.getMessage().getText();
            responseObserver.onNext(Message.newBuilder().setText(text).build());
            responseObserver.onCompleted();
        }

        @Override
        public void updateMessageV2(Message request, StreamObserver<Message> responseObserver) {
            final ServiceRequestContext ctx = ServiceRequestContext.current();
            final String messageId = Optional.ofNullable(ctx.pathParam("message_id")).orElse("no_id");
            final String text = messageId + ':' + request.getText();
            responseObserver.onNext(Message.newBuilder().setText(text).build());
            responseObserver.onCompleted();
        }

        @Override
        public void echoTimestampAndDuration(
                EchoTimestampAndDurationRequest request,
                StreamObserver<EchoTimestampAndDurationResponse> responseObserver) {
            responseObserver.onNext(EchoTimestampAndDurationResponse.newBuilder()
                                                                    .setTimestamp(request.getTimestamp())
                                                                    .setDuration(request.getDuration())
                                                                    .build());
            responseObserver.onCompleted();
        }

        @Override
        public void echoWrappers(EchoWrappersRequest request,
                                 StreamObserver<EchoWrappersResponse> responseObserver) {
            final EchoWrappersResponse.Builder builder = EchoWrappersResponse.newBuilder();
            if (request.hasDoubleVal()) {
                builder.setDoubleVal(request.getDoubleVal());
            }
            if (request.hasFloatVal()) {
                builder.setFloatVal(request.getFloatVal());
            }
            if (request.hasInt64Val()) {
                builder.setInt64Val(request.getInt64Val());
            }
            if (request.hasUint64Val()) {
                builder.setUint64Val(request.getUint64Val());
            }
            if (request.hasInt32Val()) {
                builder.setInt32Val(request.getInt32Val());
            }
            if (request.hasUint32Val()) {
                builder.setUint32Val(request.getUint32Val());
            }
            if (request.hasBoolVal()) {
                builder.setBoolVal(request.getBoolVal());
            }
            if (request.hasStringVal()) {
                builder.setStringVal(request.getStringVal());
            }
            if (request.hasBytesVal()) {
                builder.setBytesVal(request.getBytesVal());
                assertThat(request.getBytesVal().getValue().toStringUtf8()).isEqualTo(testBytesValue);
            }
            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
        }
    }

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final GrpcService grpcService = GrpcService.builder()
                                                       .addService(new HttpJsonTranscodingTestService())
                                                       .enableHttpJsonTranscoding(true)
                                                       .build();
            // gRPC transcoding will not work under '/foo'.
            // You may get the following log messages when calling the following 'serviceUnder' method:
            //   [main] WARN  c.l.armeria.server.ServerBuilder - The service has self-defined routes
            //   but the routes will be ignored. It will be served at the route you specified: path=/foo,
            //   service=...
            sb.service(grpcService)
              .requestTimeout(Duration.ZERO)
              .serviceUnder("/foo", grpcService)
              .serviceUnder("/docs", DocService.builder().build());
        }
    };

    private final ObjectMapper mapper = JacksonUtil.newDefaultObjectMapper();

    final HttpJsonTranscodingTestServiceBlockingStub grpcClient =
            Clients.builder(server.httpUri(GrpcSerializationFormats.PROTO))
                   .build(HttpJsonTranscodingTestServiceBlockingStub.class);
    final WebClient webClient = WebClient.builder(server.httpUri()).build();

    @Test
    void shouldGetMessageV1ByGrpcClient() throws Exception {
        final Message message = grpcClient.getMessageV1(
                GetMessageRequestV1.newBuilder().setName("messages/1").build());
        assertThat(message.getText()).isEqualTo("messages/1");
    }

    @Test
    void shouldGetMessageV1ByWebClient() throws Exception {
        final AggregatedHttpResponse response = webClient.get("/v1/messages/1").aggregate().get();
        final JsonNode root = mapper.readTree(response.contentUtf8());
        assertThat(root.get("text").asText()).isEqualTo("messages/1");
    }

    @Test
    void shouldGetMessageV2ByGrpcClient() throws Exception {
        final Message message = grpcClient.getMessageV2(
                GetMessageRequestV2.newBuilder()
                                   .setMessageId("1")
                                   .setRevision(999)
                                   .setSub(SubMessage.newBuilder().setSubfield("sub").build())
                                   .setType(MessageType.DETAIL)
                                   .build());
        assertThat(message.getText()).isEqualTo("1:999:sub:DETAIL");
    }

    @Test
    void shouldGetMessageV2ByWebClient() throws Exception {
        final AggregatedHttpResponse response =
                webClient.get("/v2/messages/1?revision=999&sub.subfield=sub&type=DETAIL")
                         .aggregate().get();
        final JsonNode root = mapper.readTree(response.contentUtf8());
        assertThat(root.get("text").asText()).isEqualTo("1:999:sub:DETAIL");
    }

    @Test
    void shouldGetMessageV2ByWebClient_GetDefaultValueIfUnknownEnumIsSpecified() throws Exception {
        final AggregatedHttpResponse response =
                webClient.get("/v2/messages/1?revision=999&sub.subfield=sub&type=UNKNOWN")
                         .aggregate().get();
        final JsonNode root = mapper.readTree(response.contentUtf8());
        // Return a default enum(value 0).
        assertThat(root.get("text").asText()).isEqualTo("1:999:sub:SIMPLE");
    }

    @Test
    void shouldGetMessageV3ByGrpcClient() throws Exception {
        final Message message = grpcClient.getMessageV3(
                GetMessageRequestV3.newBuilder()
                                   .setMessageId("1")
                                   .addRevision(2).addRevision(3).addRevision(4)
                                   .build());
        assertThat(message.getText()).isEqualTo("1:2:3:4");
    }

    @Test
    void shouldGetMessageV3ByWebClient() throws Exception {
        final AggregatedHttpResponse response =
                webClient.get("/v3/messages/1?revision=2&revision=3&revision=4")
                         .aggregate().get();
        final JsonNode root = mapper.readTree(response.contentUtf8());
        assertThat(root.get("text").asText()).isEqualTo("1:2:3:4");
    }

    @Test
    void shouldGetMessageV3ByWebClient_CheckOrder() throws Exception {
        final AggregatedHttpResponse response =
                webClient.get("/v3/messages/1?revision=4&revision=3&revision=2")
                         .aggregate().get();
        final JsonNode root = mapper.readTree(response.contentUtf8());
        assertThat(root.get("text").asText()).isEqualTo("1:4:3:2");
    }

    @Test
    void shouldUpdateMessageV1ByGrpcClient() throws Exception {
        final Message message = grpcClient.updateMessageV1(
                UpdateMessageRequestV1.newBuilder()
                                      .setMessageId("1")
                                      .setMessage(Message.newBuilder().setText("v1").build())
                                      .build());
        assertThat(message.getText()).isEqualTo("1:v1");
    }

    @Test
    void shouldUpdateMessageV1ByWebClient() throws Exception {
        final AggregatedHttpResponse response =
                webClient.execute(RequestHeaders.builder()
                                                .method(HttpMethod.PATCH)
                                                .path("/v1/messages/1")
                                                .contentType(MediaType.JSON)
                                                .build(),
                                  HttpData.ofUtf8("{\"text\": \"v1\"}"))
                         .aggregate().get();
        final JsonNode root = mapper.readTree(response.contentUtf8());
        assertThat(root.get("text").asText()).isEqualTo("1:v1");
    }

    @Test
    void shouldUpdateMessageV2ByGrpcClient() throws Exception {
        final Message message = grpcClient.updateMessageV2(Message.newBuilder().setText("v2").build());
        // There's no way to get 'message_id' from a gRPC request.
        assertThat(message.getText()).isEqualTo("no_id:v2");
    }

    @Test
    void shouldUpdateMessageV2ByWebClient() throws Exception {
        final AggregatedHttpResponse response =
                webClient.execute(RequestHeaders.builder()
                                                .method(HttpMethod.PATCH)
                                                .path("/v2/messages/1")
                                                .contentType(MediaType.JSON)
                                                .build(),
                                  HttpData.ofUtf8("{\"text\": \"v2\"}"))
                         .aggregate().get();
        final JsonNode root = mapper.readTree(response.contentUtf8());
        assertThat(root.get("text").asText()).isEqualTo("1:v2");
    }

    @Test
    void shouldAcceptRfc3339TimeFormatAndDuration() throws Exception {
        final String timestamp = ZonedDateTime.now().format(DateTimeFormatter.ISO_INSTANT);
        final String duration = "1.000340012s";

        final AggregatedHttpResponse response =
                webClient.get("/v1/echo/" + timestamp + '/' + duration).aggregate().get();
        final JsonNode root = mapper.readTree(response.contentUtf8());
        assertThat(root.get("timestamp").asText()).isEqualTo(timestamp);
        assertThat(root.get("duration").asText()).isEqualTo(duration);
    }

    @Test
    void shouldAcceptWrappers1() throws Exception {
        final String bytesValue = new String(
                Base64.getEncoder().encode(testBytesValue.getBytes(StandardCharsets.UTF_8)));

        final QueryParamsBuilder query = QueryParams.builder();
        query.add("doubleVal", String.valueOf(123.456d))
             .add("floatVal", String.valueOf(123.456f))
             .add("int64Val", String.valueOf(Long.MAX_VALUE))
             .add("uint64Val", String.valueOf(Long.MAX_VALUE))
             .add("int32Val", String.valueOf(Integer.MAX_VALUE))
             .add("uint32Val", String.valueOf(Integer.MAX_VALUE))
             .add("boolVal", "true")
             .add("stringVal", "string")
             .add("bytesVal", bytesValue);

        final AggregatedHttpResponse response =
                webClient.get("/v1/echo/wrappers?" + query.toQueryString()).aggregate().get();
        final JsonNode root = mapper.readTree(response.contentUtf8());
        assertThat(root.get("doubleVal").asDouble()).isEqualTo(123.456d, withPrecision(0.001d));
        assertThat(root.get("floatVal").asDouble()).isEqualTo(123.456f, withPrecision(0.001d));
        assertThat(root.get("int64Val").asLong()).isEqualTo(Long.MAX_VALUE);
        assertThat(root.get("uint64Val").asLong()).isEqualTo(Long.MAX_VALUE);
        assertThat(root.get("int32Val").asInt()).isEqualTo(Integer.MAX_VALUE);
        assertThat(root.get("uint32Val").asInt()).isEqualTo(Integer.MAX_VALUE);
        assertThat(root.get("boolVal").asBoolean()).isTrue();
        assertThat(root.get("stringVal").asText()).isEqualTo("string");
        assertThat(root.get("bytesVal").asText()).isEqualTo(bytesValue);
    }

    @Test
    void shouldAcceptWrappers2() throws Exception {
        final QueryParamsBuilder query = QueryParams.builder();
        query.add("doubleVal", String.valueOf(123.456d))
             .add("int64Val", String.valueOf(Long.MAX_VALUE))
             .add("int32Val", String.valueOf(Integer.MAX_VALUE))
             .add("stringVal", "string");

        final AggregatedHttpResponse response =
                webClient.get("/v1/echo/wrappers?" + query.toQueryString()).aggregate().get();
        final JsonNode root = mapper.readTree(response.contentUtf8());
        assertThat(root.get("doubleVal").asDouble()).isEqualTo(123.456d, withPrecision(0.001d));
        assertThat(root.get("floatVal")).isNull();
        assertThat(root.get("int64Val").asLong()).isEqualTo(Long.MAX_VALUE);
        assertThat(root.get("uint64Val")).isNull();
        assertThat(root.get("int32Val").asInt()).isEqualTo(Integer.MAX_VALUE);
        assertThat(root.get("uint32Val")).isNull();
        assertThat(root.get("boolVal")).isNull();
        assertThat(root.get("stringVal").asText()).isEqualTo("string");
        assertThat(root.get("bytesVal")).isNull();
    }

    @Test
    void shouldAcceptNaNAndInfinity() throws Exception {
        final QueryParamsBuilder query = QueryParams.builder();
        query.add("doubleVal", "NaN")
             .add("floatVal", "Infinity");

        final AggregatedHttpResponse response =
                webClient.get("/v1/echo/wrappers?" + query.toQueryString()).aggregate().get();
        final JsonNode root = mapper.readTree(response.contentUtf8());
        assertThat(root.get("doubleVal").asDouble()).isNaN();
        assertThat(root.get("floatVal").asDouble()).isInfinite();
    }

    @Test
    void shouldReturnMethodNotAllowed() throws Exception {
        final AggregatedHttpResponse response = webClient.get("/foo/").aggregate().get();
        // Because the FramedGrpcService only support HTTP POST.
        assertThat(response.status()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Test
    void shouldBeIntegratedWithDocService() throws Exception {
        final AggregatedHttpResponse response = webClient.get("/docs/specification.json").aggregate().get();
        final JsonNode root = mapper.readTree(response.contentUtf8());
        final JsonNode methods =
                StreamSupport.stream(root.get("services").spliterator(), false)
                             .filter(node -> node.get("name").asText()
                                                 .endsWith(GrpcDocServicePlugin.HTTP_SERVICE_SUFFIX))
                             .findFirst().get()
                             .get("methods");

        final JsonNode getMessageV1 = findMethod(methods, "GetMessageV1");
        assertThat(getMessageV1.get("httpMethod").asText()).isEqualTo("GET");
        assertThat(pathMapping(getMessageV1)).isEqualTo("/v1/messages/:p0");

        final JsonNode getMessageV2 = findMethod(methods, "GetMessageV2");
        assertThat(getMessageV2.get("httpMethod").asText()).isEqualTo("GET");
        assertThat(pathMapping(getMessageV2)).isEqualTo("/v2/messages/:message_id");

        final JsonNode getMessageV3 = findMethod(methods, "GetMessageV3");
        assertThat(getMessageV3.get("httpMethod").asText()).isEqualTo("GET");
        assertThat(pathMapping(getMessageV3)).isEqualTo("/v3/messages/:message_id");

        final JsonNode updateMessageV1 = findMethod(methods, "UpdateMessageV1");
        assertThat(updateMessageV1.get("httpMethod").asText()).isEqualTo("PATCH");
        assertThat(pathMapping(updateMessageV1)).isEqualTo("/v1/messages/:message_id");

        final JsonNode updateMessageV2 = findMethod(methods, "UpdateMessageV2");
        assertThat(updateMessageV2.get("httpMethod").asText()).isEqualTo("PATCH");
        assertThat(pathMapping(updateMessageV2)).isEqualTo("/v2/messages/:message_id");
    }

    private static JsonNode findMethod(JsonNode methods, String name) {
        return StreamSupport.stream(methods.spliterator(), false)
                            .filter(node -> node.get("name").asText().equals(name))
                            .findFirst().get();
    }

    private static String pathMapping(JsonNode method) {
        return method.get("endpoints").get(0).get("pathMapping").asText();
    }
}
