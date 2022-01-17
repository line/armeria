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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.TreeNode;
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
import com.linecorp.armeria.grpc.testing.Transcoding.EchoAnyRequest;
import com.linecorp.armeria.grpc.testing.Transcoding.EchoAnyResponse;
import com.linecorp.armeria.grpc.testing.Transcoding.EchoListValueRequest;
import com.linecorp.armeria.grpc.testing.Transcoding.EchoListValueResponse;
import com.linecorp.armeria.grpc.testing.Transcoding.EchoRecursiveRequest;
import com.linecorp.armeria.grpc.testing.Transcoding.EchoRecursiveResponse;
import com.linecorp.armeria.grpc.testing.Transcoding.EchoStructRequest;
import com.linecorp.armeria.grpc.testing.Transcoding.EchoStructResponse;
import com.linecorp.armeria.grpc.testing.Transcoding.EchoTimestampAndDurationRequest;
import com.linecorp.armeria.grpc.testing.Transcoding.EchoTimestampAndDurationResponse;
import com.linecorp.armeria.grpc.testing.Transcoding.EchoValueRequest;
import com.linecorp.armeria.grpc.testing.Transcoding.EchoValueResponse;
import com.linecorp.armeria.grpc.testing.Transcoding.EchoWrappersRequest;
import com.linecorp.armeria.grpc.testing.Transcoding.EchoWrappersResponse;
import com.linecorp.armeria.grpc.testing.Transcoding.GetMessageRequestV1;
import com.linecorp.armeria.grpc.testing.Transcoding.GetMessageRequestV2;
import com.linecorp.armeria.grpc.testing.Transcoding.GetMessageRequestV2.SubMessage;
import com.linecorp.armeria.grpc.testing.Transcoding.GetMessageRequestV3;
import com.linecorp.armeria.grpc.testing.Transcoding.Message;
import com.linecorp.armeria.grpc.testing.Transcoding.MessageType;
import com.linecorp.armeria.grpc.testing.Transcoding.Recursive;
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

        @Override
        public void echoStruct(EchoStructRequest request, StreamObserver<EchoStructResponse> responseObserver) {
            responseObserver.onNext(EchoStructResponse.newBuilder().setValue(request.getValue()).build());
            responseObserver.onCompleted();
        }

        @Override
        public void echoListValue(EchoListValueRequest request,
                                  StreamObserver<EchoListValueResponse> responseObserver) {
            responseObserver.onNext(EchoListValueResponse.newBuilder().setValue(request.getValue()).build());
            responseObserver.onCompleted();
        }

        @Override
        public void echoValue(EchoValueRequest request, StreamObserver<EchoValueResponse> responseObserver) {
            responseObserver.onNext(EchoValueResponse.newBuilder().setValue(request.getValue()).build());
            responseObserver.onCompleted();
        }

        @Override
        public void echoAny(EchoAnyRequest request, StreamObserver<EchoAnyResponse> responseObserver) {
            responseObserver.onNext(EchoAnyResponse.newBuilder().setValue(request.getValue()).build());
            responseObserver.onCompleted();
        }

        @Override
        public void echoRecursive(EchoRecursiveRequest request,
                                  StreamObserver<EchoRecursiveResponse> responseObserver) {
            responseObserver.onNext(EchoRecursiveResponse.newBuilder().setValue(request.getValue()).build());
            responseObserver.onCompleted();
        }

        @Override
        public void echoRecursive2(Recursive request,
                                   StreamObserver<Recursive> responseObserver) {
            responseObserver.onNext(request);
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
    void shouldGetMessageV1ByGrpcClient() {
        final Message message = grpcClient.getMessageV1(
                GetMessageRequestV1.newBuilder().setName("messages/1").build());
        assertThat(message.getText()).isEqualTo("messages/1");
    }

    @Test
    void shouldGetMessageV1ByWebClient() throws JsonProcessingException {
        final AggregatedHttpResponse response = webClient.get("/v1/messages/1").aggregate().join();
        final JsonNode root = mapper.readTree(response.contentUtf8());
        assertThat(root.get("text").asText()).isEqualTo("messages/1");
    }

    @Test
    void shouldGetMessageV2ByGrpcClient() {
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
    void shouldGetMessageV2ByWebClient() throws JsonProcessingException {
        final AggregatedHttpResponse response =
                webClient.get("/v2/messages/1?revision=999&sub.subfield=sub&type=DETAIL")
                         .aggregate().join();
        final JsonNode root = mapper.readTree(response.contentUtf8());
        assertThat(root.get("text").asText()).isEqualTo("1:999:sub:DETAIL");
    }

    @Test
    void shouldGetMessageV2ByWebClient_GetDefaultValueIfUnknownEnumIsSpecified()
            throws JsonProcessingException {
        final AggregatedHttpResponse response =
                webClient.get("/v2/messages/1?revision=999&sub.subfield=sub&type=UNKNOWN")
                         .aggregate().join();
        final JsonNode root = mapper.readTree(response.contentUtf8());
        // Return a default enum(value 0).
        assertThat(root.get("text").asText()).isEqualTo("1:999:sub:SIMPLE");
    }

    @Test
    void shouldGetMessageV3ByGrpcClient() {
        final Message message = grpcClient.getMessageV3(
                GetMessageRequestV3.newBuilder()
                                   .setMessageId("1")
                                   .addRevision(2).addRevision(3).addRevision(4)
                                   .build());
        assertThat(message.getText()).isEqualTo("1:2:3:4");
    }

    @Test
    void shouldGetMessageV3ByWebClient() throws JsonProcessingException {
        final AggregatedHttpResponse response =
                webClient.get("/v3/messages/1?revision=2&revision=3&revision=4")
                         .aggregate().join();
        final JsonNode root = mapper.readTree(response.contentUtf8());
        assertThat(root.get("text").asText()).isEqualTo("1:2:3:4");
    }

    @Test
    void shouldGetMessageV3ByWebClient_CheckOrder() throws JsonProcessingException {
        final AggregatedHttpResponse response =
                webClient.get("/v3/messages/1?revision=4&revision=3&revision=2")
                         .aggregate().join();
        final JsonNode root = mapper.readTree(response.contentUtf8());
        assertThat(root.get("text").asText()).isEqualTo("1:4:3:2");
    }

    @Test
    void shouldUpdateMessageV1ByGrpcClient() {
        final Message message = grpcClient.updateMessageV1(
                UpdateMessageRequestV1.newBuilder()
                                      .setMessageId("1")
                                      .setMessage(Message.newBuilder().setText("v1").build())
                                      .build());
        assertThat(message.getText()).isEqualTo("1:v1");
    }

    @Test
    void shouldUpdateMessageV1ByWebClient() throws JsonProcessingException {
        final AggregatedHttpResponse response =
                webClient.execute(RequestHeaders.builder()
                                                .method(HttpMethod.PATCH)
                                                .path("/v1/messages/1")
                                                .contentType(MediaType.JSON)
                                                .build(),
                                  HttpData.ofUtf8("{\"text\": \"v1\"}"))
                         .aggregate().join();
        final JsonNode root = mapper.readTree(response.contentUtf8());
        assertThat(root.get("text").asText()).isEqualTo("1:v1");
    }

    @Test
    void shouldUpdateMessageV2ByGrpcClient() {
        final Message message = grpcClient.updateMessageV2(Message.newBuilder().setText("v2").build());
        // There's no way to get 'message_id' from a gRPC request.
        assertThat(message.getText()).isEqualTo("no_id:v2");
    }

    @Test
    void shouldUpdateMessageV2ByWebClient() throws JsonProcessingException {
        final AggregatedHttpResponse response =
                webClient.execute(RequestHeaders.builder()
                                                .method(HttpMethod.PATCH)
                                                .path("/v2/messages/1")
                                                .contentType(MediaType.JSON)
                                                .build(),
                                  HttpData.ofUtf8("{\"text\": \"v2\"}"))
                         .aggregate().join();
        final JsonNode root = mapper.readTree(response.contentUtf8());
        assertThat(root.get("text").asText()).isEqualTo("1:v2");
    }

    @Test
    void shouldAcceptRfc3339TimeFormatAndDuration() throws JsonProcessingException {
        final String timestamp = ZonedDateTime.now().format(DateTimeFormatter.ISO_INSTANT);
        final String duration = "1.000340012s";

        final AggregatedHttpResponse response =
                webClient.get("/v1/echo/" + timestamp + '/' + duration).aggregate().join();
        final JsonNode root = mapper.readTree(response.contentUtf8());
        assertThat(root.get("timestamp").asText()).isEqualTo(timestamp);
        assertThat(root.get("duration").asText()).isEqualTo(duration);
    }

    @Test
    void shouldAcceptWrappers1() throws JsonProcessingException {
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
                webClient.get("/v1/echo/wrappers?" + query.toQueryString()).aggregate().join();
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
    void shouldAcceptWrappers2() throws JsonProcessingException {
        final QueryParamsBuilder query = QueryParams.builder();
        query.add("doubleVal", String.valueOf(123.456d))
             .add("int64Val", String.valueOf(Long.MAX_VALUE))
             .add("int32Val", String.valueOf(Integer.MAX_VALUE))
             .add("stringVal", "string");

        final AggregatedHttpResponse response =
                webClient.get("/v1/echo/wrappers?" + query.toQueryString()).aggregate().join();
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
    void shouldAcceptStruct() throws JsonProcessingException {
        final String jsonContent = "{\"intVal\": 1, \"stringVal\": \"1\"}";
        final AggregatedHttpResponse response = jsonPostRequest(webClient, "/v1/echo/struct", jsonContent);
        final JsonNode root = mapper.readTree(response.contentUtf8());
        final JsonNode value = root.get("value");
        assertThat(value).isNotNull().matches(v -> ((TreeNode) v).isObject());
        assertThat(value.get("intVal").asInt()).isOne();
        assertThat(value.get("stringVal").asText()).isEqualTo("1");
    }

    @Test
    void shouldAcceptListValue_String() throws JsonProcessingException {
        final String jsonContent = "[\"1\", \"2\"]";
        final AggregatedHttpResponse response = jsonPostRequest(webClient, "/v1/echo/list_value", jsonContent);
        final JsonNode root = mapper.readTree(response.contentUtf8());
        final JsonNode value = root.get("value");
        assertThat(value.isArray()).isTrue();
        assertThat(value.get(0).asText()).isEqualTo("1");
        assertThat(value.get(1).asText()).isEqualTo("2");
    }

    @Test
    void shouldAcceptListValue_Number() throws JsonProcessingException {
        final String jsonContent = "[1, 2]";
        final AggregatedHttpResponse response = jsonPostRequest(webClient, "/v1/echo/list_value", jsonContent);
        final JsonNode root = mapper.readTree(response.contentUtf8());
        final JsonNode value = root.get("value");
        assertThat(value.isArray()).isTrue();
        assertThat(value.get(0).asInt()).isEqualTo(1);
        assertThat(value.get(1).asInt()).isEqualTo(2);
    }

    @Test
    void shouldAcceptValue_String() throws JsonProcessingException {
        final String jsonContent = "\"1\"";
        final AggregatedHttpResponse response = jsonPostRequest(webClient, "/v1/echo/value", jsonContent);
        final JsonNode root = mapper.readTree(response.contentUtf8());
        assertThat(root.get("value").asText()).isEqualTo("1");
    }

    @Test
    void shouldAcceptValue_Number() throws JsonProcessingException {
        final String jsonContent = "1";
        final AggregatedHttpResponse response = jsonPostRequest(webClient, "/v1/echo/value", jsonContent);
        final JsonNode root = mapper.readTree(response.contentUtf8());
        assertThat(root.get("value").asInt()).isEqualTo(1);
    }

    @Test
    void shouldAcceptAny() throws JsonProcessingException {
        final String jsonContent =
                '{' +
                "  \"@type\": \"type.googleapis.com/google.protobuf.Duration\"," +
                "  \"value\": \"1.212s\"" +
                '}';
        final AggregatedHttpResponse response = jsonPostRequest(webClient, "/v1/echo/any", jsonContent);
        final JsonNode root = mapper.readTree(response.contentUtf8());
        final JsonNode value = root.get("value");
        assertThat(value).isNotNull().matches(v -> ((TreeNode) v).isObject());
        assertThat(value.get("@type").asText()).isEqualTo("type.googleapis.com/google.protobuf.Duration");
        assertThat(value.get("value").asText()).isEqualTo("1.212s");
    }

    @Test
    void shouldDenyHttpGetParameters_Struct_Value_ListValue_Any() {
        assertThat(webClient.get("/v1/echo/struct?value=1").aggregate().join().status())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(webClient.get("/v1/echo/list_value?value=1&value=2").aggregate().join().status())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(webClient.get("/v1/echo/value?value=1").aggregate().join().status())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(webClient.get("/v1/echo/any?value=1").aggregate().join().status())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void shouldAcceptRecursive() throws JsonProcessingException {
        final String jsonContent =
                '{' +
                "  \"value\": \"a\"," +
                "  \"nested\": {" +
                "    \"value\": \"b\"" +
                "  }" +
                '}';
        final AggregatedHttpResponse response = jsonPostRequest(webClient, "/v1/echo/recursive", jsonContent);
        final JsonNode root = mapper.readTree(response.contentUtf8());
        final JsonNode value = root.get("value");
        assertThat(value).isNotNull().matches(v -> ((TreeNode) v).isObject());
        assertThat(value.get("value").asText()).isEqualTo("a");
        final JsonNode nested = value.get("nested");
        assertThat(nested).isNotNull().matches(v -> ((TreeNode) v).isObject());
        assertThat(nested.get("value").asText()).isEqualTo("b");
    }

    @Test
    void shouldAcceptRecursive2() throws JsonProcessingException {
        final String jsonContent =
                '{' +
                "  \"value\": \"a\"," +
                "  \"nested\": {" +
                "    \"value\": \"b\"" +
                "  }" +
                '}';
        final AggregatedHttpResponse response = jsonPostRequest(webClient, "/v1/echo/recursive2", jsonContent);
        final JsonNode root = mapper.readTree(response.contentUtf8());
        assertThat(root.get("value").asText()).isEqualTo("a");
        final JsonNode nested = root.get("nested");
        assertThat(nested).isNotNull().matches(v -> ((TreeNode) v).isObject());
        assertThat(nested.get("value").asText()).isEqualTo("b");
    }

    @Test
    void shoudDenyRecursiveViaHttpGet() {
        assertThat(webClient.get("/v1/echo/recursive?value=a").aggregate().join().status())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(webClient.get("/v1/echo/recursive2?nested=a").aggregate().join().status())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        // Note that the parameter will be ignored because it won't be matched with any fields.
        assertThat(webClient.get("/v1/echo/recursive?value.nested.value=a").aggregate().join().status())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void shouldAcceptNaNAndInfinity() throws JsonProcessingException {
        final QueryParamsBuilder query = QueryParams.builder();
        query.add("doubleVal", "NaN")
             .add("floatVal", "Infinity");

        final AggregatedHttpResponse response =
                webClient.get("/v1/echo/wrappers?" + query.toQueryString()).aggregate().join();
        final JsonNode root = mapper.readTree(response.contentUtf8());
        assertThat(root.get("doubleVal").asDouble()).isNaN();
        assertThat(root.get("floatVal").asDouble()).isInfinite();
    }

    @Test
    void shouldReturnMethodNotAllowed() {
        final AggregatedHttpResponse response = webClient.get("/foo/").aggregate().join();
        // Because the FramedGrpcService only support HTTP POST.
        assertThat(response.status()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Test
    void shouldBeIntegratedWithDocService() throws JsonProcessingException {
        final AggregatedHttpResponse response = webClient.get("/docs/specification.json").aggregate().join();
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

    private static AggregatedHttpResponse jsonPostRequest(WebClient webClient, String path, String body) {
        final RequestHeaders headers = RequestHeaders.builder().method(HttpMethod.POST).path(path)
                                                     .contentType(MediaType.JSON).build();
        return webClient.execute(headers, body).aggregate().join();
    }
}
