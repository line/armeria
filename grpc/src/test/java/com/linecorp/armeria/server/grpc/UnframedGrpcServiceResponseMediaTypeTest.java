/*
 * Copyright 2022 LINE Corporation
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

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;

import io.grpc.BindableService;
import io.grpc.stub.StreamObserver;
import testing.grpc.EmptyProtos;
import testing.grpc.TestServiceGrpc;

public class UnframedGrpcServiceResponseMediaTypeTest {

    @RegisterExtension
    static EventLoopExtension eventLoop = new EventLoopExtension();

    private static class TestService extends TestServiceGrpc.TestServiceImplBase {

        @Override
        public void emptyCall(EmptyProtos.Empty request, StreamObserver<EmptyProtos.Empty> responseObserver) {
            responseObserver.onNext(EmptyProtos.Empty.newBuilder().build());
            responseObserver.onCompleted();
        }
    }

    private static final TestService testService = new TestService();
    private static final int MAX_MESSAGE_BYTES = 1024;

    @Test
    void respondWithCorrespondingJsonMediaType() throws Exception {
        final UnframedGrpcService unframedGrpcService = buildUnframedGrpcService(testService);

        final HttpRequest request = HttpRequest.of(HttpMethod.POST,
                "/armeria.grpc.testing.TestService/EmptyCall",
                MediaType.JSON_UTF_8, "{}");
        final ServiceRequestContext ctx = ServiceRequestContext.builder(request)
                                                               .build();

        final AggregatedHttpResponse res = unframedGrpcService.serve(ctx, request).aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentType()).isEqualTo(MediaType.JSON_UTF_8);
    }

    @ParameterizedTest
    @ArgumentsSource(ProtobufMediaTypeProvider.class)
    void respondWithCorrespondingProtobufMediaType(MediaType protobufType) throws Exception {
        final UnframedGrpcService unframedGrpcService = buildUnframedGrpcService(testService);

        final HttpRequest request = HttpRequest.of(HttpMethod.POST,
                                                   "/armeria.grpc.testing.TestService/EmptyCall",
                                                   protobufType,
                                                   EmptyProtos.Empty.getDefaultInstance().toByteArray());
        final ServiceRequestContext ctx = ServiceRequestContext.builder(request)
                                                               .build();

        final AggregatedHttpResponse res = unframedGrpcService.serve(ctx, request).aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.contentType()).isEqualTo(protobufType);
    }

    private static class ProtobufMediaTypeProvider implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
            return Stream.of(MediaType.PROTOBUF, MediaType.X_PROTOBUF, MediaType.X_GOOGLE_PROTOBUF)
                         .map(Arguments::of);
        }
    }

    private static UnframedGrpcService buildUnframedGrpcService(BindableService bindableService) {
        return (UnframedGrpcService) GrpcService.builder()
                                                .addService(bindableService)
                                                .maxRequestMessageLength(MAX_MESSAGE_BYTES)
                                                .maxResponseMessageLength(MAX_MESSAGE_BYTES)
                                                .enableUnframedRequests(true)
                                                .build();
    }
}
