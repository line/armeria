/*
 * Copyright 2019 LINE Corporation
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

package com.linecorp.armeria.grpc;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import com.google.protobuf.Empty;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaMessageFramer;
import com.linecorp.armeria.grpc.shared.GithubApiService;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.grpc.GrpcService;

import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;

@State(Scope.Thread)
public class GrpcServiceBenchmark {

    private static final HttpService SERVICE =
            GrpcService.builder()
                       .addService(new GithubApiService())
                       .build();

    private static final byte[] FRAMED_EMPTY;

    static {
        try (HttpData data = new ArmeriaMessageFramer(ByteBufAllocator.DEFAULT, 0, false)
                .writePayload(Unpooled.wrappedBuffer(Empty.getDefaultInstance().toByteArray()))) {
            FRAMED_EMPTY = data.array();
        }
    }

    private static final RequestHeaders EMPTY_HEADERS =
            RequestHeaders.of(HttpMethod.POST, '/' + GithubServiceGrpc.getEmptyMethod().getFullMethodName(),
                              HttpHeaderNames.CONTENT_TYPE, GrpcSerializationFormats.PROTO.mediaType());

    private HttpRequest req;
    private ServiceRequestContext ctx;
    private HttpResponse response;

    @Setup(Level.Invocation)
    public void initBuffers() {
        req = HttpRequest.of(EMPTY_HEADERS,
                             HttpData.wrap(ByteBufAllocator.DEFAULT.buffer().writeBytes(FRAMED_EMPTY)));
        ctx = ServiceRequestContext.builder(req)
                                   .service(SERVICE)
                                   .build();
    }

    @TearDown(Level.Invocation)
    public void closeResponse() {
        response.aggregate().join();
    }

    @Benchmark
    public HttpResponse empty() throws Exception {
        return response = SERVICE.serve(ctx, req);
    }
}
