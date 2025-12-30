/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.stream.Collectors;

import com.google.api.HttpBody;

import com.linecorp.armeria.server.ServiceRequestContext;

import io.grpc.stub.StreamObserver;
import testing.grpc.HttpJsonTranscodingTestServiceGrpc.HttpJsonTranscodingTestServiceImplBase;
import testing.grpc.Transcoding.ArbitraryHttpWrappedRequest;
import testing.grpc.Transcoding.ArbitraryHttpWrappedResponse;
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
import testing.grpc.Transcoding.GetMessageRequestV3;
import testing.grpc.Transcoding.GetMessageRequestV4;
import testing.grpc.Transcoding.GetMessageRequestV5;
import testing.grpc.Transcoding.Message;
import testing.grpc.Transcoding.Recursive;
import testing.grpc.Transcoding.UpdateMessageRequestV1;

class HttpJsonTranscodingTestService extends HttpJsonTranscodingTestServiceImplBase {

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

    @Override
    public void arbitraryHttp(HttpBody request, StreamObserver<HttpBody> responseObserver) {
        final HttpBody.Builder builder = HttpBody.newBuilder();
        builder.setContentType(request.getContentType())
               .setData(request.getData());
        responseObserver.onNext(builder.build());
        responseObserver.onCompleted();
    }

    @Override
    public void arbitraryHttpWrapped(ArbitraryHttpWrappedRequest request,
                                     StreamObserver<ArbitraryHttpWrappedResponse> responseObserver) {
        final ArbitraryHttpWrappedResponse.Builder builder = ArbitraryHttpWrappedResponse.newBuilder();
        builder.setResponseId(request.getRequestId() + "-response");
        builder.setBody(request.getBody());
        responseObserver.onNext(builder.build());
        responseObserver.onCompleted();
    }
}
