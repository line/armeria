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

/**
 * Provides Armeria's default implementation for server-side HTTP request and response parsers.
 * Users may use the default parser like the following:
 * <pre>{@code
 * Tracing tracing = ...
 * HttpTracing httpTracing =
 *   HttpTracing.newBuilder(tracing)
 *              .serverRequestParser((req, ctx, span) -> {
 *                  // Apply Brave's default request parser
 *                  HttpRequestParser.DEFAULT.parse(req, ctx, span);
 *                  // Apply Armeria's default request parser
 *                  BraveHttpServerParsers.requestParser().parse(req, ctx, span);
 *              })
 *              .serverResponseParser((res, ctx, span) -> {
 *                  // Apply Brave's default response parser
 *                  HttpResponseParser.DEFAULT.parse(res, ctx, span);
 *                  // Apply Armeria's default response parser
 *                  BraveHttpServerParsers.responseParser().parse(res, ctx, span);
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
 */
@UnstableApi
public final class BraveHttpServerParsers {

    private static final HttpRequestParser defaultRequestParser = ArmeriaServerParser::parseRequest;

    private static final HttpResponseParser defaultResponseParser = ArmeriaServerParser::parseResponse;

    /**
     * Returns the default {@link HttpRequestParser}.
     */
    public static HttpRequestParser requestParser() {
        return defaultRequestParser;
    }

    /**
     * Returns the default {@link HttpResponseParser}.
     */
    public static HttpResponseParser responseParser() {
        return defaultResponseParser;
    }

    private BraveHttpServerParsers() {}
}
