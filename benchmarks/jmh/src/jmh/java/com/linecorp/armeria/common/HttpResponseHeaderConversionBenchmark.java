/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.common;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServiceRequestContext;

@State(Scope.Benchmark)
public class HttpResponseHeaderConversionBenchmark {

    private Server serverWithAdditionalHeaders;

    private WebClient clientWithoutAdditionalHeadersHttp1;
    private WebClient clientWithoutAdditionalHeadersHttp2;

    @Setup
    public void startServer() {
        final int port = 8080;

        serverWithAdditionalHeaders = Server.builder()
                                            .http(port)
                                            .service("/header_conversion", (ctx, req) -> {
                           addAdditionalHeaders(ctx);
                           addProhibitedHeaders(ctx);
                           return HttpResponse.of(HttpStatus.OK);
                       })
                                            .build();
        serverWithAdditionalHeaders.start().join();

        clientWithoutAdditionalHeadersHttp1 = WebClient.of("h1c://127.0.0.1:" + port);
        clientWithoutAdditionalHeadersHttp2 = WebClient.of("h2c://127.0.0.1:" + port);
    }

    private static void addAdditionalHeaders(ServiceRequestContext ctx) {
        ctx.addAdditionalResponseHeader("custom-header-1", "my-header-1");
        ctx.addAdditionalResponseHeader("custom-header-2", "my-header-2");
        ctx.addAdditionalResponseHeader("custom-header-3", "my-header-3");
        ctx.addAdditionalResponseHeader("custom-header-4", "my-header-4");

        ctx.addAdditionalResponseTrailer("custom-trailer-1", "my-trailer-1");
        ctx.addAdditionalResponseTrailer("custom-trailer-2", "my-trailer-2");
        ctx.addAdditionalResponseTrailer("custom-trailer-3", "my-trailer-3");
        ctx.addAdditionalResponseTrailer("custom-trailer-4", "my-trailer-4");
    }

    private static void addProhibitedHeaders(ServiceRequestContext ctx) {
        ctx.addAdditionalResponseHeader(HttpHeaderNames.SCHEME, "https");
        ctx.addAdditionalResponseHeader(HttpHeaderNames.STATUS, "100");
        ctx.addAdditionalResponseHeader(HttpHeaderNames.METHOD, "CONNECT");
        ctx.addAdditionalResponseHeader(HttpHeaderNames.PATH, "/foo");

        ctx.addAdditionalResponseTrailer(HttpHeaderNames.SCHEME, "https");
        ctx.addAdditionalResponseTrailer(HttpHeaderNames.STATUS, "100");
        ctx.addAdditionalResponseTrailer(HttpHeaderNames.METHOD, "CONNECT");
        ctx.addAdditionalResponseTrailer(HttpHeaderNames.PATH, "/foo");
        ctx.addAdditionalResponseTrailer(HttpHeaderNames.TRANSFER_ENCODING, "magic");
    }

    @TearDown
    public void stopServer() {
        serverWithAdditionalHeaders.stop().join();
    }

    @Benchmark
    public void http1HeaderConversionBenchmark() {
        clientWithoutAdditionalHeadersHttp1.get("/header_conversion").aggregate().join();
    }

    @Benchmark
    public void http2HeaderConversionBenchmark() {
        clientWithoutAdditionalHeadersHttp2.get("/header_conversion").aggregate().join();
    }
}
