/*
 * Copyright 2016 LINE Corporation
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
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc.TestServiceImplBase;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.RoutingResult;
import com.linecorp.armeria.server.ServiceRequestContext;

// Tests error cases, success cases are checked in ArmeriaGrpcServiceInteropTest
class FramedGrpcServiceTest {

    private final FramedGrpcService grpcService =
            (FramedGrpcService) GrpcService.builder()
                                           .addService(mock(TestServiceImplBase.class))
                                           .build();

    @Test
    void missingContentType() throws Exception {
        final HttpRequest req = HttpRequest.of(HttpMethod.POST, "/grpc.testing.TestService.UnaryCall");
        final ServiceRequestContext ctx = ServiceRequestContext.of(req);
        final HttpResponse response = grpcService.doPost(ctx, req);
        assertThat(response.aggregate().get()).isEqualTo(AggregatedHttpResponse.of(
                ResponseHeaders.of(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                                   HttpHeaderNames.CONTENT_TYPE, MediaType.PLAIN_TEXT_UTF_8,
                                   HttpHeaderNames.CONTENT_LENGTH, 39),
                HttpData.ofUtf8("Missing or invalid Content-Type header.")));
    }

    @Test
    void badContentType() throws Exception {
        final HttpRequest req = HttpRequest.of(
                RequestHeaders.of(HttpMethod.POST, "/grpc.testing.TestService.UnaryCall",
                                  HttpHeaderNames.CONTENT_TYPE, MediaType.JSON_UTF_8));
        final ServiceRequestContext ctx = ServiceRequestContext.of(req);
        final HttpResponse response = grpcService.doPost(ctx, req);
        assertThat(response.aggregate().get()).isEqualTo(AggregatedHttpResponse.of(
                ResponseHeaders.of(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                                   HttpHeaderNames.CONTENT_TYPE, MediaType.PLAIN_TEXT_UTF_8,
                                   HttpHeaderNames.CONTENT_LENGTH, 39),
                HttpData.ofUtf8("Missing or invalid Content-Type header.")));
    }

    @Test
    void missingMethod() throws Exception {
        final HttpRequest req = HttpRequest.of(
                RequestHeaders.of(HttpMethod.POST, "/grpc.testing.TestService/FooCall",
                                  HttpHeaderNames.CONTENT_TYPE, "application/grpc+proto"));
        final RoutingResult routingResult = RoutingResult.builder()
                                                         .path("/grpc.testing.TestService/FooCall")
                                                         .build();
        final ServiceRequestContext ctx = ServiceRequestContext.builder(req)
                                                               .routingResult(routingResult)
                                                               .build();
        final HttpResponse response = grpcService.doPost(ctx, req);
        assertThat(response.aggregate().get()).isEqualTo(AggregatedHttpResponse.of(
                ResponseHeaders.builder(HttpStatus.OK)
                               .endOfStream(true)
                               .add(HttpHeaderNames.CONTENT_TYPE, "application/grpc+proto")
                               .add(GrpcHeaderNames.GRPC_ENCODING, "identity")
                               .add(GrpcHeaderNames.GRPC_ACCEPT_ENCODING, "gzip")
                               .addInt("grpc-status", 12)
                               .add("grpc-message", "Method not found: /grpc.testing.TestService/FooCall")
                               .addInt(HttpHeaderNames.CONTENT_LENGTH, 0)
                               .build(),
                HttpData.empty()));
    }

    @Test
    public void routes() throws Exception {
        assertThat(grpcService.routes())
                .containsExactlyInAnyOrder(
                        Route.builder().exact("/armeria.grpc.testing.TestService/EmptyCall").build(),
                        Route.builder().exact("/armeria.grpc.testing.TestService/UnaryCall").build(),
                        Route.builder().exact("/armeria.grpc.testing.TestService/UnaryCall2").build(),
                        Route.builder().exact("/armeria.grpc.testing.TestService/StreamingOutputCall").build(),
                        Route.builder().exact("/armeria.grpc.testing.TestService/StreamingInputCall").build(),
                        Route.builder().exact("/armeria.grpc.testing.TestService/FullDuplexCall").build(),
                        Route.builder().exact("/armeria.grpc.testing.TestService/HalfDuplexCall").build(),
                        Route.builder().exact("/armeria.grpc.testing.TestService/UnimplementedCall").build());
    }
}
