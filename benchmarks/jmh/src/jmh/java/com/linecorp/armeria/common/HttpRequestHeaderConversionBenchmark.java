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

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.server.Server;

@State(Scope.Benchmark)
public class HttpRequestHeaderConversionBenchmark {

    private Server serverWithoutAdditionalHeaders;

    private WebClient clientWithAdditionalHeadersHttp1;
    private WebClient clientWithAdditionalHeadersHttp2;

    @Setup
    public void startServer() {
        final int port = 8080;

        serverWithoutAdditionalHeaders = Server.builder()
                                               .http(port)
                                               .service("/header_conversion", (ctx, req) -> {
                                                   return HttpResponse.of(HttpStatus.OK);
                                               })
                                               .build();
        serverWithoutAdditionalHeaders.start().join();

        clientWithAdditionalHeadersHttp1 = WebClient.builder("h1c://127.0.0.1:" + port)
                                                    .decorator(((delegate, ctx, req) -> {
                                                        addAdditionalHeaders(ctx);
                                                        addProhibitedHeaders(ctx);
                                                        addCookies(ctx);
                                                        return delegate.execute(ctx, req);
                                                    })).build();

        clientWithAdditionalHeadersHttp2 = WebClient.builder("h2c://127.0.0.1:" + port)
                                                    .decorator(((delegate, ctx, req) -> {
                                                        addAdditionalHeaders(ctx);
                                                        addProhibitedHeaders(ctx);
                                                        addCookies(ctx);
                                                        return delegate.execute(ctx, req);
                                                    })).build();
    }

    private static void addAdditionalHeaders(ClientRequestContext ctx) {
        ctx.addAdditionalRequestHeader("custom-header-1", "my-header-1");
        ctx.addAdditionalRequestHeader("custom-header-2", "my-header-2");
        ctx.addAdditionalRequestHeader("custom-header-3", "my-header-3");
        ctx.addAdditionalRequestHeader("custom-header-4", "my-header-4");
    }

    private static void addProhibitedHeaders(ClientRequestContext ctx) {
        ctx.addAdditionalRequestHeader(HttpHeaderNames.SCHEME, "https");
        ctx.addAdditionalRequestHeader(HttpHeaderNames.STATUS, "503");
        ctx.addAdditionalRequestHeader(HttpHeaderNames.METHOD, "CONNECT");
    }

    private static void addCookies(ClientRequestContext ctx) {
        ctx.addAdditionalRequestHeader(HttpHeaderNames.COOKIE, "a=b; c=d");
        ctx.addAdditionalRequestHeader(HttpHeaderNames.COOKIE, "e=f; g=h");
        ctx.addAdditionalRequestHeader(HttpHeaderNames.COOKIE, "i=j; k=l");
    }

    @TearDown
    public void stopServer() {
        serverWithoutAdditionalHeaders.stop().join();
    }

    @Benchmark
    public void http1HeaderConversionBenchmark() {
        clientWithAdditionalHeadersHttp1.get("/header_conversion").aggregate().join();
    }

    @Benchmark
    public void http2HeaderConversionBenchmark() {
        clientWithAdditionalHeadersHttp2.get("/header_conversion").aggregate().join();
    }
}
