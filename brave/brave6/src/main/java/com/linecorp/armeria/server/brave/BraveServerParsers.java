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

package com.linecorp.armeria.server.brave;

import com.linecorp.armeria.common.annotation.UnstableApi;

import brave.http.HttpRequestParser;
import brave.http.HttpResponseParser;
import brave.rpc.RpcRequestParser;
import brave.rpc.RpcResponseParser;

/**
 * Provides Armeria's default implementation for server-side request and response parsers.
 *
 * <p>Users may use HTTP parsers like the following:
 * <pre>{@code
 * Tracing tracing = ...
 * HttpTracing httpTracing =
 *   HttpTracing.newBuilder(tracing)
 *              .serverRequestParser((req, ctx, span) -> {
 *                  // Apply Brave's default request parser
 *                  HttpRequestParser.DEFAULT.parse(req, ctx, span);
 *                  // Apply Armeria's default request parser
 *                  BraveServerParsers.httpRequestParser().parse(req, ctx, span);
 *              })
 *              .serverResponseParser((res, ctx, span) -> {
 *                  // Apply Brave's default response parser
 *                  HttpResponseParser.DEFAULT.parse(res, ctx, span);
 *                  // Apply Armeria's default response parser
 *                  BraveServerParsers.httpResponseParser().parse(res, ctx, span);
 *              });
 * BraveService
 *   .newDecorator(httpTracing)
 *   ...
 * }</pre>
 * The following tags will be available by default:
 * <ul>
 *   <li>http.url</li>
 *   <li>http.host</li>
 *   <li>http.protocol</li>
 *   <li>http.serfmt</li>
 *   <li>address.remote</li>
 *   <li>address.local</li>
 * </ul>
 *
 * <p>Conversely, RPC parsers may be used similarly
 * <pre>{@code
 * Tracing tracing = ...
 * RpcTracing rpcTracing =
 *   RpcTracing.newBuilder(tracing)
 *              .serverRequestParser((req, ctx, span) -> {
 *                  // Apply Brave's default request parser
 *                  RpcRequestParser.DEFAULT.parse(req, ctx, span);
 *                  // Apply Armeria's default request parser
 *                  BraveServerParsers.rpcRequestParser().parse(req, ctx, span);
 *              })
 *              .serverResponseParser((res, ctx, span) -> {
 *                  // Apply Brave's default response parser
 *                  RpcResponseParser.DEFAULT.parse(res, ctx, span);
 *                  // Apply Armeria's default response parser
 *                  BraveServerParsers.rpcResponseParser().parse(res, ctx, span);
 *              });
 * BraveRpcService
 *   .newDecorator(rpcTracing)
 *   ...
 * }</pre>
 * The following tags will be available by default:
 * <ul>
 *   <li>http.url</li>
 *   <li>http.host</li>
 *   <li>http.protocol</li>
 *   <li>http.serfmt</li>
 *   <li>address.remote</li>
 *   <li>address.local</li>
 * </ul>
 */
@UnstableApi
public final class BraveServerParsers {

    private static final RpcRequestParser defaultRpcRequestParser = ArmeriaServerParser::parseRequest;

    private static final RpcResponseParser defaultRpcResponseParser = ArmeriaServerParser::parseResponse;

    private static final HttpRequestParser defaultHttpRequestParser = ArmeriaServerParser::parseRequest;

    private static final HttpResponseParser defaultHttpResponseParser = ArmeriaServerParser::parseResponse;

    /**
     * Returns the default {@link HttpRequestParser}.
     */
    public static HttpRequestParser httpRequestParser() {
        return defaultHttpRequestParser;
    }

    /**
     * Returns the default {@link HttpResponseParser}.
     */
    public static HttpResponseParser httpResponseParser() {
        return defaultHttpResponseParser;
    }

    /**
     * Returns the default {@link RpcRequestParser}.
     */
    public static RpcRequestParser rpcRequestParser() {
        return defaultRpcRequestParser;
    }

    /**
     * Returns the default {@link RpcResponseParser}.
     */
    public static RpcResponseParser rpcResponseParser() {
        return defaultRpcResponseParser;
    }

    private BraveServerParsers() {}
}
