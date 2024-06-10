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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.linecorp.armeria.it.grpc.HttpJsonTranscodingTest.HttpJsonTranscodingTestService.testBytesValue;
import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.withPrecision;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.TreeNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Streams;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.QueryParamsBuilder;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.grpc.GrpcJsonMarshaller;
import com.linecorp.armeria.internal.common.JacksonUtil;
import com.linecorp.armeria.internal.server.grpc.GrpcDocServicePlugin;
import com.linecorp.armeria.internal.testing.GenerateNativeImageTrace;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.docs.DocService;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.server.grpc.GrpcServiceBuilder;
import com.linecorp.armeria.server.grpc.HttpJsonTranscodingOptions;
import com.linecorp.armeria.server.grpc.HttpJsonTranscodingQueryParamMatchRule;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.Status.Code;
import io.grpc.stub.StreamObserver;
import testing.grpc.HttpJsonTranscodingTestServiceGrpc.HttpJsonTranscodingTestServiceBlockingStub;
import testing.grpc.HttpJsonTranscodingTestServiceGrpc.HttpJsonTranscodingTestServiceImplBase;
import testing.grpc.Transcoding.EchoAnyRequest;
import testing.grpc.Transcoding.EchoAnyResponse;
import testing.grpc.Transcoding.EchoFieldMaskRequest;
import testing.grpc.Transcoding.EchoFieldMaskResponse;
import testing.grpc.Transcoding.EchoListValueRequest;
import testing.grpc.Transcoding.EchoListValueResponse;
import testing.grpc.Transcoding.EchoNestedMessageRequest;
import testing.grpc.Transcoding.EchoNestedMessageResponse;
import testing.grpc.Transcoding.EchoRecursiveRequest;
import testing.grpc.Transcoding.EchoRecursiveResponse;
import testing.grpc.Transcoding.EchoResponseBodyRequest;
import testing.grpc.Transcoding.EchoResponseBodyResponse;
import testing.grpc.Transcoding.EchoStructRequest;
import testing.grpc.Transcoding.EchoStructResponse;
import testing.grpc.Transcoding.EchoTimestampAndDurationRequest;
import testing.grpc.Transcoding.EchoTimestampAndDurationResponse;
import testing.grpc.Transcoding.EchoTimestampRequest;
import testing.grpc.Transcoding.EchoTimestampResponse;
import testing.grpc.Transcoding.EchoValueRequest;
import testing.grpc.Transcoding.EchoValueResponse;
import testing.grpc.Transcoding.EchoWrappersRequest;
import testing.grpc.Transcoding.EchoWrappersResponse;
import testing.grpc.Transcoding.GetMessageRequestV1;
import testing.grpc.Transcoding.GetMessageRequestV2;
import testing.grpc.Transcoding.GetMessageRequestV2.SubMessage;
import testing.grpc.Transcoding.GetMessageRequestV3;
import testing.grpc.Transcoding.GetMessageRequestV4;
import testing.grpc.Transcoding.GetMessageRequestV5;
import testing.grpc.Transcoding.Message;
import testing.grpc.Transcoding.MessageType;
import testing.grpc.Transcoding.Recursive;
import testing.grpc.Transcoding.UpdateMessageRequestV1;

// The public Static methods in this class are used by the classes in other packages.
@GenerateNativeImageTrace
public class HttpJsonTranscodingTest {

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
        public void getMessageV4(GetMessageRequestV4 request, StreamObserver<Message> responseObserver) {
            final String text = request.getMessageId() + ':' +
                                request.getQueryParameter() + ':' +
                                request.getParentField().getChildField() + ':' +
                                request.getParentField().getChildField2();
            responseObserver.onNext(Message.newBuilder().setText(text).build());
            responseObserver.onCompleted();
        }

        @Override
        public void getMessageV5(GetMessageRequestV5 request, StreamObserver<Message> responseObserver) {
            final String text = request.getMessageId() + ':' +
                                request.getQueryParameter() + ':' +
                                request.getQueryField1() + ':' +
                                request.getParentField().getChildField() + ':' +
                                request.getParentField().getChildField2();
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
        public void echoTimestamp(
                EchoTimestampRequest request,
                StreamObserver<EchoTimestampResponse> responseObserver) {
            responseObserver.onNext(EchoTimestampResponse.newBuilder()
                                                         .setTimestamp(request.getTimestamp())
                                                         .build());
            responseObserver.onCompleted();
        }

        @Override
        public void echoFieldMask(EchoFieldMaskRequest request,
                                  StreamObserver<EchoFieldMaskResponse> responseObserver) {
            responseObserver.onNext(EchoFieldMaskResponse.newBuilder()
                                                         .setFieldMask(request.getFieldMask())
                                                         .setPathCount(request.getFieldMask()
                                                                              .getPathsList().size())
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

        static EchoResponseBodyResponse getResponseBodyResponse(EchoResponseBodyRequest request) {
            return EchoResponseBodyResponse.newBuilder()
                                           .setValue(request.getValue())
                                           .addAllArrayField(request.getArrayFieldList())
                                           .setStructBody(request.getStructBody())
                                           .build();
        }

        @Override
        public void echoResponseBodyValue(EchoResponseBodyRequest request,
                                          StreamObserver<EchoResponseBodyResponse> responseObserver) {
            responseObserver.onNext(getResponseBodyResponse(request));
            responseObserver.onCompleted();
        }

        @Override
        public void echoResponseBodyRepeated(EchoResponseBodyRequest request,
                                             StreamObserver<EchoResponseBodyResponse>
                                                     responseObserver) {
            responseObserver.onNext(getResponseBodyResponse(request));
            responseObserver.onCompleted();
        }

        @Override
        public void echoResponseBodyStruct(EchoResponseBodyRequest request,
                                           StreamObserver<EchoResponseBodyResponse>
                                                   responseObserver) {
            responseObserver.onNext(getResponseBodyResponse(request));
            responseObserver.onCompleted();
        }

        @Override
        public void echoResponseBodyNoMatching(EchoResponseBodyRequest request,
                                               StreamObserver<EchoResponseBodyResponse>
                                                       responseObserver) {
            responseObserver.onNext(getResponseBodyResponse(request));
            responseObserver.onCompleted();
        }

        @Override
        public void echoNestedMessageField(EchoNestedMessageRequest request,
                                           StreamObserver<EchoNestedMessageResponse> responseObserver) {
            responseObserver
                    .onNext(EchoNestedMessageResponse.newBuilder().setNested(request.getNested()).build());
            responseObserver.onCompleted();
        }
    }

    @RegisterExtension
    static final ServerExtension server = createServer(false, false, true);

    @RegisterExtension
    static final ServerExtension serverPreservingProtoFieldNames = createServer(true, false, true);

    @RegisterExtension
    static final ServerExtension serverCamelCaseQueryOnlyParameters = createServer(false, true, false);

    @RegisterExtension
    static final ServerExtension serverCamelCaseQueryAndOriginalParameters = createServer(false, true, true);

    private final ObjectMapper mapper = JacksonUtil.newDefaultObjectMapper();

    private final WebClient webClient = WebClient.builder(server.httpUri()).build();

    private final WebClient webClientPreservingProtoFieldNames =
            WebClient.builder(serverPreservingProtoFieldNames.httpUri()).build();

    private final BlockingWebClient webClientCamelCaseQueryOnlyParameters =
            serverCamelCaseQueryOnlyParameters.blockingWebClient();

    private final BlockingWebClient webClientCamelCaseQueryAndOriginalParameters =
            serverCamelCaseQueryAndOriginalParameters.blockingWebClient();

    static ServerExtension createServer(boolean preservingProtoFieldNames, boolean camelCaseQueryParams,
                                        boolean protoFieldNameQueryParams) {
        final ImmutableList.Builder<HttpJsonTranscodingQueryParamMatchRule> queryParamMatchRules =
                ImmutableList.builder();
        if (camelCaseQueryParams) {
            queryParamMatchRules.add(HttpJsonTranscodingQueryParamMatchRule.LOWER_CAMEL_CASE);
        }
        if (protoFieldNameQueryParams) {
            queryParamMatchRules.add(HttpJsonTranscodingQueryParamMatchRule.ORIGINAL_FIELD);
        }
        final HttpJsonTranscodingOptions options =
                HttpJsonTranscodingOptions.builder()
                                          .queryParamMatchRules(queryParamMatchRules.build())
                                          .build();
        return new ServerExtension() {
            @Override
            protected void configure(ServerBuilder sb) throws Exception {
                final GrpcServiceBuilder grpcServiceBuilder =
                        GrpcService.builder()
                                   .addService(new HttpJsonTranscodingTestService())
                                   .enableHttpJsonTranscoding(options);
                if (preservingProtoFieldNames) {
                    grpcServiceBuilder.jsonMarshallerFactory(service -> GrpcJsonMarshaller
                            .builder()
                            .jsonMarshallerCustomizer(m -> m.preservingProtoFieldNames(true))
                            .build(service));
                }
                final GrpcService grpcService = grpcServiceBuilder.build();
                sb.service(grpcService)
                  .requestTimeout(Duration.ZERO)
                  .serviceUnder("/foo", grpcService)
                  .serviceUnder("/docs", DocService.builder().build());
            }
        };
    }

    @ParameterizedTest
    @ArgumentsSource(BlockingClientProvider.class)
    void shouldGetMessageV1ByGrpcClient(HttpJsonTranscodingTestServiceBlockingStub client) {
        final Message message = client.getMessageV1(
                GetMessageRequestV1.newBuilder().setName("messages/1").build());
        assertThat(message.getText()).isEqualTo("messages/1");
    }

    @ParameterizedTest
    @ValueSource(strings = { "/", "/foo/" })
    void shouldGetMessageV1ByWebClient(String prefix) throws JsonProcessingException {
        final AggregatedHttpResponse response = webClient.get(prefix + "v1/messages/1").aggregate().join();
        final JsonNode root = mapper.readTree(response.contentUtf8());
        assertThat(response.contentType()).isEqualTo(MediaType.JSON_UTF_8);
        assertThat(root.get("text").asText()).isEqualTo("messages/1");
    }

    @ParameterizedTest
    @ValueSource(strings = { "/", "/foo/" })
    void shouldPostMessageV1ByWebClient(String prefix) throws JsonProcessingException {
        final AggregatedHttpResponse response = webClient.post(prefix + "v1/messages/1:get", "").aggregate()
                                                         .join();
        final JsonNode root = mapper.readTree(response.contentUtf8());
        assertThat(response.contentType()).isEqualTo(MediaType.JSON_UTF_8);
        assertThat(root.get("text").asText()).isEqualTo("messages/1");
    }

    @ParameterizedTest
    @ArgumentsSource(BlockingClientProvider.class)
    void shouldGetMessageV2ByGrpcClient(HttpJsonTranscodingTestServiceBlockingStub client) {
        final Message message = client.getMessageV2(
                GetMessageRequestV2.newBuilder()
                                   .setMessageId("1")
                                   .setRevision(999)
                                   .setSub(SubMessage.newBuilder().setSubfield("sub").build())
                                   .setType(MessageType.DETAIL)
                                   .build());
        assertThat(message.getText()).isEqualTo("1:999:sub:DETAIL");
    }

    @ParameterizedTest
    @ValueSource(strings = { "/", "/foo/" })
    void shouldGetMessageV2ByWebClient(String prefix) throws JsonProcessingException {
        final AggregatedHttpResponse response =
                webClient.get(prefix + "v2/messages/1?revision=999&sub.subfield=sub&type=DETAIL")
                         .aggregate().join();
        final JsonNode root = mapper.readTree(response.contentUtf8());
        assertThat(response.contentType()).isEqualTo(MediaType.JSON_UTF_8);
        assertThat(root.get("text").asText()).isEqualTo("1:999:sub:DETAIL");
    }

    @ParameterizedTest
    @ValueSource(strings = { "/", "/foo/" })
    void shouldGetMessageV2ByWebClient_GetDefaultValueIfUnknownEnumIsSpecified()
            throws JsonProcessingException {
        final AggregatedHttpResponse response =
                webClient.get("/v2/messages/1?revision=999&sub.subfield=sub&type=UNKNOWN")
                         .aggregate().join();
        final JsonNode root = mapper.readTree(response.contentUtf8());
        assertThat(response.contentType()).isEqualTo(MediaType.JSON_UTF_8);
        // Return a default enum(value 0).
        assertThat(root.get("text").asText()).isEqualTo("1:999:sub:SIMPLE");
    }

    @ParameterizedTest
    @ArgumentsSource(BlockingClientProvider.class)
    void shouldGetMessageV3ByGrpcClient(HttpJsonTranscodingTestServiceBlockingStub client) {
        final Message message = client.getMessageV3(
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
        assertThat(response.contentType()).isEqualTo(MediaType.JSON_UTF_8);
        assertThat(root.get("text").asText()).isEqualTo("1:2:3:4");
    }

    @Test
    void shouldGetMessageV3ByWebClient_CheckOrder() throws JsonProcessingException {
        final AggregatedHttpResponse response =
                webClient.get("/v3/messages/1?revision=4&revision=3&revision=2")
                         .aggregate().join();
        final JsonNode root = mapper.readTree(response.contentUtf8());
        assertThat(response.contentType()).isEqualTo(MediaType.JSON_UTF_8);
        assertThat(root.get("text").asText()).isEqualTo("1:4:3:2");
    }

    @ParameterizedTest
    @ArgumentsSource(BlockingClientProvider.class)
    void shouldUpdateMessageV1ByGrpcClient(HttpJsonTranscodingTestServiceBlockingStub client) {
        final Message message = client.updateMessageV1(
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
        assertThat(response.contentType()).isEqualTo(MediaType.JSON_UTF_8);
        assertThat(root.get("text").asText()).isEqualTo("1:v1");
    }

    @ParameterizedTest
    @ArgumentsSource(BlockingClientProvider.class)
    void shouldUpdateMessageV2ByGrpcClient(HttpJsonTranscodingTestServiceBlockingStub client) {
        final Message message = client.updateMessageV2(Message.newBuilder().setText("v2").build());
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
        assertThat(response.contentType()).isEqualTo(MediaType.JSON_UTF_8);
        assertThat(root.get("text").asText()).isEqualTo("1:v2");
    }

    @Test
    void shouldAcceptRfc3339TimeFormatAndDuration() throws JsonProcessingException {
        final String timestamp = ZonedDateTime.now().format(DateTimeFormatter.ISO_INSTANT);
        final String duration = "1.000340012s";

        final AggregatedHttpResponse response =
                webClient.get("/v1/echo/timestamp/" + timestamp + '/' + duration).aggregate().join();
        final JsonNode root = mapper.readTree(response.contentUtf8());
        assertThat(response.contentType()).isEqualTo(MediaType.JSON_UTF_8);
        assertThat(root.get("timestamp").asText()).isEqualTo(timestamp);
        assertThat(root.get("duration").asText()).isEqualTo(duration);
    }

    @Test
    void shouldAcceptRfc3339TimeFormat() throws JsonProcessingException {
        final String timestamp = ZonedDateTime.now().format(DateTimeFormatter.ISO_INSTANT);

        final AggregatedHttpResponse response =
                webClient.post("/v1/echo/timestamp/" + timestamp + ":get", "").aggregate().join();
        final JsonNode root = mapper.readTree(response.contentUtf8());
        assertThat(response.contentType()).isEqualTo(MediaType.JSON_UTF_8);
        assertThat(root.get("timestamp").asText()).isEqualTo(timestamp);
    }

    @Test
    void shouldAcceptFieldMaskAsString() throws JsonProcessingException {
        final String fieldMask = "a,b,c";
        final AggregatedHttpResponse response =
                webClient.get("/v1/echo/field_mask?field_mask=" + fieldMask).aggregate().join();
        final JsonNode root = mapper.readTree(response.contentUtf8());
        assertThat(response.contentType()).isEqualTo(MediaType.JSON_UTF_8);
        assertThat(root.get("fieldMask").asText()).isEqualTo(fieldMask);
        assertThat(root.get("pathCount").asInt()).isEqualTo(3);
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
        assertThat(response.contentType()).isEqualTo(MediaType.JSON_UTF_8);
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
        assertThat(response.contentType()).isEqualTo(MediaType.JSON_UTF_8);
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
        assertThat(response.contentType()).isEqualTo(MediaType.JSON_UTF_8);
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
        assertThat(response.contentType()).isEqualTo(MediaType.JSON_UTF_8);
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
        assertThat(response.contentType()).isEqualTo(MediaType.JSON_UTF_8);
        assertThat(value.isArray()).isTrue();
        assertThat(value.get(0).asInt()).isEqualTo(1);
        assertThat(value.get(1).asInt()).isEqualTo(2);
    }

    @Test
    void shouldAcceptValue_String() throws JsonProcessingException {
        final String jsonContent = "\"1\"";
        final AggregatedHttpResponse response = jsonPostRequest(webClient, "/v1/echo/value", jsonContent);
        final JsonNode root = mapper.readTree(response.contentUtf8());
        assertThat(response.contentType()).isEqualTo(MediaType.JSON_UTF_8);
        assertThat(root.get("value").asText()).isEqualTo("1");
    }

    @Test
    void shouldAcceptValue_Number() throws JsonProcessingException {
        final String jsonContent = "1";
        final AggregatedHttpResponse response = jsonPostRequest(webClient, "/v1/echo/value", jsonContent);
        final JsonNode root = mapper.readTree(response.contentUtf8());
        assertThat(response.contentType()).isEqualTo(MediaType.JSON_UTF_8);
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
        assertThat(response.contentType()).isEqualTo(MediaType.JSON_UTF_8);
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
        assertThat(response.contentType()).isEqualTo(MediaType.JSON_UTF_8);
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
        assertThat(response.contentType()).isEqualTo(MediaType.JSON_UTF_8);
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
        assertThat(response.contentType()).isEqualTo(MediaType.JSON_UTF_8);
        assertThat(root.get("doubleVal").asDouble()).isNaN();
        assertThat(root.get("floatVal").asDouble()).isInfinite();
    }

    @Test
    void shouldAcceptResponseBodyValue() {
        final QueryParamsBuilder query = QueryParams.builder();
        query.add("value", "value");
        final AggregatedHttpResponse response = webClient.get("/v1/echo/response_body/value?" +
                                                              query.toQueryString())
                                                         .aggregate().join();
        assertThat(response.contentType()).isEqualTo(MediaType.JSON_UTF_8);
        assertThat(response.contentUtf8()).isEqualTo("\"value\"");
    }

    @Test
    void shouldAcceptResponseBodyRepeated() throws JsonProcessingException {
        final AggregatedHttpResponse response =
                webClient.get("/v1/echo/response_body/repeated?array_field=value1&array_field=value2")
                         .aggregate().join();
        final JsonNode root = mapper.readTree(response.contentUtf8());
        assertThat(response.contentType()).isEqualTo(MediaType.JSON_UTF_8);
        assertThat(root.isArray()).isTrue();
        assertThatJson(root).isEqualTo("[\"value1\",\"value2\"]");
    }

    @Test
    void shouldAcceptResponseBodyValueStruct() throws JsonProcessingException {
        final String jsonContent = "{\"value\":\"value\",\"structBody\":{\"structBody\":\"struct_value\"}," +
                                   "\"arrayField\":[\"value1\",\"value2\"]}";
        final AggregatedHttpResponse response = jsonPostRequest(webClient,
                                                                "/v1/echo/response_body/struct", jsonContent);
        final JsonNode root = mapper.readTree(response.contentUtf8());
        assertThat(response.contentType()).isEqualTo(MediaType.JSON_UTF_8);
        assertThat(root.has("structBody")).isTrue();
        assertThat(root.get("structBody").asText()).isEqualTo("struct_value");
    }

    @Test
    void shouldAcceptResponseBodyValueNullValue() throws JsonProcessingException {
        final String jsonContent = "{\"value\":\"value\"," +
                                   "\"arrayField\":[\"value1\",\"value2\"]}";
        final AggregatedHttpResponse response = jsonPostRequest(webClient,
                                                                "/v1/echo/response_body/struct", jsonContent);
        final JsonNode root = mapper.readTree(response.contentUtf8());
        assertThat(response.contentType()).isEqualTo(MediaType.JSON_UTF_8);
        assertThat(root.isEmpty()).isTrue();
    }

    @Test
    void shouldAcceptResponseBodyValueAnonymusField() throws JsonProcessingException {
        final String jsonContent = "{\"value\":\"value\",\"structBody\":{\"structBody\":\"struct_value\"}" +
                                   ",\"arrayField\":[\"value1\",\"value2\"]}";
        final AggregatedHttpResponse response = jsonPostRequest(webClient,
                                                                "/v1/echo/response_body/nomatch", jsonContent);
        final JsonNode root = mapper.readTree(response.contentUtf8());
        assertThat(response.contentType()).isEqualTo(MediaType.JSON_UTF_8);
        assertThatJson(root).isEqualTo("{\"value\":\"value\"," +
                                       "\"structBody\":{\"structBody\":\"struct_value\"}," +
                                       "\"arrayField\":[\"value1\",\"value2\"]}");
    }

    @Test
    void shouldAcceptResponseBodyValueNoMatchInside() throws JsonProcessingException {
        final String jsonContent = "{\"value\":\"value\",\"structBody\":{\"structBody\":\"struct_value\"} }";
        final AggregatedHttpResponse response = jsonPostRequest(
                webClient, "/v1/echo/response_body/repeated", jsonContent);
        assertThat(response.contentType()).isEqualTo(MediaType.JSON_UTF_8);
        assertThat(response.contentUtf8()).isEqualTo("null");
    }

    @Test
    void shouldDenyMalformedJson() throws JsonProcessingException {
        final String jsonContent = "{\"value\":\"value\",\"structBody\":{\"structBody\":\"struct_value\"}";
        final AggregatedHttpResponse response =
                jsonPostRequest(webClient, "/v1/echo/response_body/repeated", jsonContent);
        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"int32Val\": 1.1}",
            "{\"int64Val\": 2.2}",
            "{\"uint32Val\": 3.3}",
            "{\"uint64Val\": 4.4}"
    })
    void shouldDenyTypeMismatchedValue(String jsonContent)
            throws JsonProcessingException {
        final AggregatedHttpResponse response = jsonPostRequest(webClient, "/v1/echo/wrappers", jsonContent);
        final JsonNode root = mapper.readTree(response.contentUtf8());
        assertThat(response.headers().status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(root.get("code").asInt()).isEqualTo(Code.INVALID_ARGUMENT.value());
    }

    @Test
    void shouldDenyMissingContentType() {
        final String validJson = "{\"value\":\"value\",\"structBody\":{\"structBody\":\"struct_value\"} }";
        final RequestHeaders headers = RequestHeaders.builder()
                                                     .method(HttpMethod.POST)
                                                     .path("/v1/echo/response_body/repeated")
                                                     .build();
        final AggregatedHttpResponse response = webClient.execute(headers, validJson).aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void shouldDenyNonJsonContentType() {
        final String validJson = "{\"value\":\"value\",\"structBody\":{\"structBody\":\"struct_value\"} }";
        final RequestHeaders headers = RequestHeaders.builder()
                                                     .method(HttpMethod.POST)
                                                     .path("/v1/echo/response_body/repeated")
                                                     .contentType(MediaType.CSV_UTF_8)
                                                     .build();
        final AggregatedHttpResponse response = webClient.execute(headers, validJson).aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void shouldDenyEmptyJson() {
        final String emptyJson = "";
        final RequestHeaders headers = RequestHeaders.builder()
                                                     .method(HttpMethod.POST)
                                                     .path("/v1/echo/response_body/repeated")
                                                     .contentType(MediaType.JSON)
                                                     .build();
        final AggregatedHttpResponse response = webClient.execute(headers, emptyJson).aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void shouldAcceptEmptyNonJson() {
        final String body = "";
        final RequestHeaders headers = RequestHeaders.builder()
                                                     .method(HttpMethod.POST)
                                                     .path("/v1/echo/response_body/repeated")
                                                     .build();
        final AggregatedHttpResponse response = webClient.execute(headers, body).aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void shouldDenyNonObjectJson() {
        final String body = "[ 42, null ]";
        final RequestHeaders headers = RequestHeaders.builder()
                                                     .method(HttpMethod.POST)
                                                     .path("/v1/echo/response_body/repeated")
                                                     .build();
        final AggregatedHttpResponse response = webClient.execute(headers, body).aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void shouldAcceptResponseBodyValueStructPreservingProtoFieldNames() throws JsonProcessingException {
        final String jsonContent = "{\"value\":\"value\",\"structBody\":{\"structBody\":\"struct_value\"}," +
                                   "\"arrayField\":[\"value1\",\"value2\"]}";
        final AggregatedHttpResponse response = jsonPostRequest(webClientPreservingProtoFieldNames,
                                                                "/v1/echo/response_body/struct", jsonContent);
        final JsonNode root = mapper.readTree(response.contentUtf8());
        assertThat(response.contentType()).isEqualTo(MediaType.JSON_UTF_8);
        assertThat(root.has("struct_body")).isTrue();
        assertThat(root.get("struct_body").asText()).isEqualTo("struct_value");
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
        assertThat(pathMapping(getMessageV1)).containsExactlyInAnyOrder("/v1/messages/:p0",
                                                                        "/foo/v1/messages/:p0");

        final JsonNode getMessageV2 = findMethod(methods, "GetMessageV2");
        assertThat(getMessageV2.get("httpMethod").asText()).isEqualTo("GET");
        assertThat(pathMapping(getMessageV2)).containsExactlyInAnyOrder("/v2/messages/:message_id",
                                                                        "/foo/v2/messages/:message_id");

        final JsonNode getMessageV3 = findMethod(methods, "GetMessageV3");
        assertThat(getMessageV3.get("httpMethod").asText()).isEqualTo("GET");
        assertThat(pathMapping(getMessageV3)).containsExactlyInAnyOrder("/v3/messages/:message_id",
                                                                        "/foo/v3/messages/:message_id");

        final JsonNode updateMessageV1 = findMethod(methods, "UpdateMessageV1");
        assertThat(updateMessageV1.get("httpMethod").asText()).isEqualTo("PATCH");
        assertThat(pathMapping(updateMessageV1)).containsExactlyInAnyOrder("/v1/messages/:message_id",
                                                                           "/foo/v1/messages/:message_id");

        final JsonNode updateMessageV2 = findMethod(methods, "UpdateMessageV2");
        assertThat(updateMessageV2.get("httpMethod").asText()).isEqualTo("PATCH");
        assertThat(pathMapping(updateMessageV2)).containsExactlyInAnyOrder("/v2/messages/:message_id",
                                                                           "/foo/v2/messages/:message_id");
    }

    @Test
    void shouldAcceptOnlyCamelCaseQueryParams() throws JsonProcessingException {
        final QueryParams query =
                QueryParams.builder()
                           .add("queryParameter", "testQuery")
                           .add("parentField.childField", "testChildField")
                           .add("parentField.childField2", "testChildField2")
                           .build();

        final JsonNode response =
                webClientCamelCaseQueryOnlyParameters.prepare()
                                                     .get("/v4/messages/1")
                                                     .queryParams(query)
                                                     .asJson(JsonNode.class)
                                                     .execute()
                                                     .content();
        assertThat(response.get("text").asText()).isEqualTo("1:testQuery:testChildField:testChildField2");

        final QueryParams query2 =
                QueryParams.builder()
                           .add("query_parameter", "testQuery")
                           .add("parent_field.child_field", "testChildField")
                           .add("parent_field.child_field_2", "testChildField2")
                           .build();

        final JsonNode response2 =
                webClientCamelCaseQueryOnlyParameters.prepare()
                                                     .get("/v4/messages/1")
                                                     .queryParams(query2)
                                                     .asJson(JsonNode.class)
                                                     .execute()
                                                     .content();
        // Disallow snake_case parameters.
        assertThat(response2.get("text").asText()).isEqualTo("1:::");
    }

    @Test
    void shouldAcceptBothCamelCaseAndSnakeCaseQueryParams() throws JsonProcessingException {
        final QueryParams query =
                QueryParams.builder()
                           .add("queryParameter", "testQuery")
                           .add("parentField.childField", "testChildField")
                           .add("parentField.childField2", "testChildField2")
                           .build();

        final JsonNode response =
                webClientCamelCaseQueryAndOriginalParameters.prepare()
                                                            .get("/v4/messages/1")
                                                            .queryParams(query)
                                                            .asJson(JsonNode.class)
                                                            .execute()
                                                            .content();
        assertThat(response.get("text").asText()).isEqualTo("1:testQuery:testChildField:testChildField2");

        final QueryParams query2 =
                QueryParams.builder()
                           .add("query_parameter", "testQuery")
                           .add("parent_field.child_field", "testChildField")
                           .add("parent_field.child_field_2", "testChildField2")
                           .build();

        final JsonNode response2 =
                webClientCamelCaseQueryAndOriginalParameters.prepare()
                                                            .get("/v4/messages/1")
                                                            .queryParams(query2)
                                                            .asJson(JsonNode.class)
                                                            .execute()
                                                            .content();
        assertThat(response2.get("text").asText()).isEqualTo("1:testQuery:testChildField:testChildField2");
    }

    @Test
    void supportJsonName() {
        final QueryParams query =
                QueryParams.builder()
                           .add("query_parameter", "query")
                           .add("second_query", "query2")
                           .add("parent.child_field", "childField")
                           .add("parent.second_field", "childField2")
                           .build();

        final JsonNode response =
                webClientCamelCaseQueryAndOriginalParameters.prepare()
                                                            .get("/v5/messages/1")
                                                            .queryParams(query)
                                                            .asJson(JsonNode.class)
                                                            .execute()
                                                            .content();
        assertThat(response.get("text").asText()).isEqualTo("1:query:query2:childField:childField2");
    }

    @Test
    void shouldAcceptNestedMessageTypeFields() throws JsonProcessingException {
        final String jsonContent =
                '{' +
                "  \"nested\": {" +
                "    \"name\": \"Armeria\"" +
                "  }" +
                '}';
        final AggregatedHttpResponse response = jsonPostRequest(webClient,
                                                                "/v1/echo/nested_message", jsonContent);
        final JsonNode root = mapper.readTree(response.contentUtf8());
        final JsonNode nested = root.get("nested");
        assertThat(response.contentType()).isEqualTo(MediaType.JSON_UTF_8);
        assertThat(nested).isNotNull().matches(v -> ((TreeNode) v).isObject());
        assertThat(nested.get("name").asText()).isEqualTo("Armeria");
    }

    public static JsonNode findMethod(JsonNode methods, String name) {
        return StreamSupport.stream(methods.spliterator(), false)
                            .filter(node -> node.get("name").asText().equals(name))
                            .findFirst().get();
    }

    public static List<String> pathMapping(JsonNode method) {
        return Streams.stream(method.get("endpoints")).map(node -> node.get("pathMapping").asText())
                      .collect(toImmutableList());
    }

    private static AggregatedHttpResponse jsonPostRequest(WebClient webClient, String path, String body) {
        final RequestHeaders headers = RequestHeaders.builder().method(HttpMethod.POST).path(path)
                                                     .contentType(MediaType.JSON).build();
        return webClient.execute(headers, body).aggregate().join();
    }

    private static class BlockingClientProvider implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
            return Stream.of(GrpcClients.builder(server.httpUri())
                                        .build(HttpJsonTranscodingTestServiceBlockingStub.class),
                             GrpcClients.builder(server.httpUri()).pathPrefix("/foo/")
                                        .build(HttpJsonTranscodingTestServiceBlockingStub.class))
                         .map(Arguments::of);
        }
    }
}
