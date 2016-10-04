/*
 * Copyright 2016 LINE Corporation
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.concurrent.Executors;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import com.linecorp.armeria.common.http.AggregatedHttpMessage;
import com.linecorp.armeria.common.http.DefaultHttpResponse;
import com.linecorp.armeria.common.http.HttpData;
import com.linecorp.armeria.common.http.HttpHeaderNames;
import com.linecorp.armeria.common.http.HttpHeaders;
import com.linecorp.armeria.common.http.HttpMethod;
import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.http.HttpStatus;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.grpc.internal.GrpcUtil;
import io.grpc.testing.integration.TestServiceImpl;
import io.netty.util.AsciiString;

// Tests error cases, success cases are checked in ArmeriaGrpcServiceInteropTest
public class GrpcServiceTest {

    @Rule
    public MockitoRule mocks = MockitoJUnit.rule();

    @Mock
    private ServiceRequestContext ctx;

    private DefaultHttpResponse response;

    private GrpcService grpcService;

    @Before
    public void setUp() {
        response = new DefaultHttpResponse();
        grpcService = new GrpcServiceBuilder()
                .addService(new TestServiceImpl(Executors.newSingleThreadScheduledExecutor()))
                .build();
    }

    @Test
    public void missingContentType() throws Exception {
        grpcService.doPost(
                ctx,
                HttpRequest.of(HttpHeaders.of(HttpMethod.POST, "/grpc.testing.TestService.UnaryCall")),
                response);
        assertThat(response.aggregate().get()).isEqualTo(AggregatedHttpMessage.of(
                HttpHeaders.of(HttpStatus.BAD_REQUEST)
                           .set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8"),
                HttpData.ofUtf8("Missing or invalid Content-Type header.")));
    }

    @Test
    public void badContentType() throws Exception {
        grpcService.doPost(
                ctx,
                HttpRequest.of(HttpHeaders.of(HttpMethod.POST, "/grpc.testing.TestService.UnaryCall")
                                          .set(HttpHeaderNames.CONTENT_TYPE, "application/json")),
                response);
        assertThat(response.aggregate().get()).isEqualTo(AggregatedHttpMessage.of(
                HttpHeaders.of(HttpStatus.BAD_REQUEST)
                           .set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8"),
                HttpData.ofUtf8("Missing or invalid Content-Type header.")));
    }

    @Test
    public void pathMissingSlash() throws Exception {
        when(ctx.mappedPath()).thenReturn("grpc.testing.TestService.UnaryCall");
        grpcService.doPost(
                ctx,
                HttpRequest.of(HttpHeaders.of(HttpMethod.POST, "grpc.testing.TestService.UnaryCall")
                                          .set(HttpHeaderNames.CONTENT_TYPE, GrpcUtil.CONTENT_TYPE_GRPC)),
                response);
        assertThat(response.aggregate().get()).isEqualTo(AggregatedHttpMessage.of(
                HttpHeaders.of(HttpStatus.BAD_REQUEST)
                           .set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8"),
                HttpData.ofUtf8("Invalid path.")));
    }

    @Test
    public void missingMethod() throws Exception {
        when(ctx.mappedPath()).thenReturn("/grpc.testing.TestService/FooCall");
        grpcService.doPost(
                ctx,
                HttpRequest.of(HttpHeaders.of(HttpMethod.POST, "/grpc.testing.TestService/FooCall")
                                          .set(HttpHeaderNames.CONTENT_TYPE, GrpcUtil.CONTENT_TYPE_GRPC)),
                response);
        assertThat(response.aggregate().get()).isEqualTo(AggregatedHttpMessage.of(
                HttpHeaders.of(HttpStatus.OK)
                           .set(HttpHeaderNames.CONTENT_TYPE, GrpcUtil.CONTENT_TYPE_GRPC)
                           .set(AsciiString.of("grpc-status"), "12")
                           .set(AsciiString.of("grpc-message"),
                                "Method not found: grpc.testing.TestService/FooCall")
                           .set(HttpHeaderNames.CONTENT_LENGTH, "0"),
                HttpData.of(new byte[] {})));
    }
}
