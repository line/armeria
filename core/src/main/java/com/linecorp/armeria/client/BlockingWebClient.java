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

package com.linecorp.armeria.client;

import static java.util.Objects.requireNonNull;

import java.nio.charset.Charset;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.BlockingTaskExecutor;
import com.linecorp.armeria.common.util.Unwrappable;

import io.netty.channel.EventLoop;

/**
 * A blocking web client that waits for a {@link HttpRequest} to be fully aggregated.
 *
 * <p>Note that a blocking request should be sent in a non-{@link EventLoop} thread such
 * {@link BlockingTaskExecutor}.
 */
@UnstableApi
public interface BlockingWebClient extends ClientBuilderParams, Unwrappable {

    /**
     * Returns a {@link BlockingWebClient} without a base URI using the default {@link ClientFactory} and
     * the default {@link ClientOptions}.
     */
    static BlockingWebClient of() {
        return DefaultBlockingWebClient.DEFAULT;
    }

    /**
     * Sends the specified HTTP request.
     */
    default AggregatedHttpResponse execute(HttpRequest req) {
        return execute(req, RequestOptions.of());
    }

    // TODO(ikhoon): Add a test to make sure `BlockingWebClient` has the same APIs with `WebClient`.

    /**
     * Sends the specified HTTP request with the specified {@link RequestOptions}.
     */
    AggregatedHttpResponse execute(HttpRequest req, RequestOptions options);

    /**
     * Sends the specified HTTP request.
     */
    default AggregatedHttpResponse execute(AggregatedHttpRequest aggregatedReq) {
        requireNonNull(aggregatedReq, "aggregatedReq");
        return execute(aggregatedReq.toHttpRequest());
    }

    /**
     * Sends an empty HTTP request with the specified headers.
     */
    default AggregatedHttpResponse execute(RequestHeaders headers) {
        return execute(HttpRequest.of(headers));
    }

    /**
     * Sends an HTTP request with the specified headers and content.
     */
    default AggregatedHttpResponse execute(RequestHeaders headers, HttpData content) {
        return execute(HttpRequest.of(headers, content));
    }

    /**
     * Sends an HTTP request with the specified headers and content.
     */
    default AggregatedHttpResponse execute(RequestHeaders headers, byte[] content) {
        return execute(HttpRequest.of(headers, HttpData.wrap(content)));
    }

    /**
     * Sends an HTTP request with the specified headers and content.
     */
    default AggregatedHttpResponse execute(RequestHeaders headers, String content) {
        return execute(HttpRequest.of(headers, HttpData.ofUtf8(content)));
    }

    /**
     * Sends an HTTP request with the specified headers and content.
     */
    default AggregatedHttpResponse execute(RequestHeaders headers, String content, Charset charset) {
        return execute(HttpRequest.of(headers, HttpData.of(charset, content)));
    }

    /**
     * Prepares to send an {@link HttpRequest} using fluent builder.
     * <pre>{@code
     * WebClient webClient = WebClient.of(...);
     * BlockingWebClient client = webClient.blocking();
     * AggregatedHttpResponse response = client.prepare()
     *                                         .post("/foo")
     *                                         .header(HttpHeaderNames.AUTHORIZATION, ...)
     *                                         .content(MediaType.JSON, ...)
     *                                         .execute();
     * }</pre>
     */
    BlockingWebClientRequestPreparation prepare();

    /**
     * Sends an HTTP OPTIONS request.
     */
    default AggregatedHttpResponse options(String path) {
        return execute(RequestHeaders.of(HttpMethod.OPTIONS, path));
    }

    /**
     * Sends an HTTP OPTIONS request, accepts query parameters to append to the path.
     */
    default AggregatedHttpResponse options(String path, QueryParams params) {
        return execute(RequestHeaders.of(HttpMethod.OPTIONS, WebClientUtil.addQueryParams(path, params)));
    }

    /**
     * Sends an HTTP GET request.
     */
    default AggregatedHttpResponse get(String path) {
        return execute(RequestHeaders.of(HttpMethod.GET, path));
    }

    /**
     * Sends an HTTP GET request, accepts query parameters to append to the path.
     */
    default AggregatedHttpResponse get(String path, QueryParams params) {
        return execute(RequestHeaders.of(HttpMethod.GET, WebClientUtil.addQueryParams(path, params)));
    }

    /**
     * Sends an HTTP HEAD request.
     */
    default AggregatedHttpResponse head(String path) {
        return execute(RequestHeaders.of(HttpMethod.HEAD, path));
    }

    /**
     * Sends an HTTP HEAD request, accepts query parameters to append to the path.
     */
    default AggregatedHttpResponse head(String path, QueryParams params) {
        return execute(RequestHeaders.of(HttpMethod.HEAD, WebClientUtil.addQueryParams(path, params)));
    }

    /**
     * Sends an HTTP POST request with the specified content.
     */
    default AggregatedHttpResponse post(String path, HttpData content) {
        return execute(RequestHeaders.of(HttpMethod.POST, path), content);
    }

    /**
     * Sends an HTTP POST request with the specified content, accepts query parameters to append to the path.
     */
    default AggregatedHttpResponse post(String path, QueryParams params, HttpData content) {
        return execute(RequestHeaders.of(HttpMethod.POST, WebClientUtil.addQueryParams(path, params)), content);
    }

    /**
     * Sends an HTTP POST request with the specified content.
     */
    default AggregatedHttpResponse post(String path, byte[] content) {
        return execute(RequestHeaders.of(HttpMethod.POST, path), content);
    }

    /**
     * Sends an HTTP POST request with the specified content, accepts query parameters to append to the path.
     */
    default AggregatedHttpResponse post(String path, QueryParams params, byte[] content) {
        return execute(RequestHeaders.of(HttpMethod.POST, WebClientUtil.addQueryParams(path, params)), content);
    }

    /**
     * Sends an HTTP POST request with the specified content.
     */
    default AggregatedHttpResponse post(String path, String content) {
        return execute(RequestHeaders.of(HttpMethod.POST, path), HttpData.ofUtf8(content));
    }

    /**
     * Sends an HTTP POST request with the specified content, accepts query parameters to append to the path.
     */
    default AggregatedHttpResponse post(String path, QueryParams params, String content) {
        return execute(RequestHeaders.of(HttpMethod.POST, WebClientUtil.addQueryParams(path, params)), content);
    }

    /**
     * Sends an HTTP POST request with the specified content.
     */
    default AggregatedHttpResponse post(String path, String content, Charset charset) {
        return execute(RequestHeaders.of(HttpMethod.POST, path), content, charset);
    }

    /**
     * Sends an HTTP POST request with the specified content, accepts query parameters to append to the path.
     */
    default AggregatedHttpResponse post(String path, QueryParams params, String content, Charset charset) {
        return execute(RequestHeaders.of(HttpMethod.POST,
                                         WebClientUtil.addQueryParams(path, params)), content, charset);
    }

    /**
     * Sends an HTTP PUT request with the specified content.
     */
    default AggregatedHttpResponse put(String path, HttpData content) {
        return execute(RequestHeaders.of(HttpMethod.PUT, path), content);
    }

    /**
     * Sends an HTTP PUT request with the specified content, accepts query parameters to append to the path.
     */
    default AggregatedHttpResponse put(String path, QueryParams params, HttpData content) {
        return execute(RequestHeaders.of(HttpMethod.PUT,
                                         WebClientUtil.addQueryParams(path, params)), content);
    }

    /**
     * Sends an HTTP PUT request with the specified content.
     */
    default AggregatedHttpResponse put(String path, byte[] content) {
        return execute(RequestHeaders.of(HttpMethod.PUT, path), content);
    }

    /**
     * Sends an HTTP PUT request with the specified content, accepts query parameters to append to the path.
     */
    default AggregatedHttpResponse put(String path, QueryParams params, byte[] content) {
        return execute(RequestHeaders.of(HttpMethod.PUT,
                                         WebClientUtil.addQueryParams(path, params)), content);
    }

    /**
     * Sends an HTTP PUT request with the specified content.
     */
    default AggregatedHttpResponse put(String path, String content) {
        return execute(RequestHeaders.of(HttpMethod.PUT, path), HttpData.ofUtf8(content));
    }

    /**
     * Sends an HTTP PUT request with the specified content, accepts query parameters to append to the path.
     */
    default AggregatedHttpResponse put(String path, QueryParams params, String content) {
        return execute(RequestHeaders.of(HttpMethod.PUT,
                                         WebClientUtil.addQueryParams(path, params)), content);
    }

    /**
     * Sends an HTTP PUT request with the specified content.
     */
    default AggregatedHttpResponse put(String path, String content, Charset charset) {
        return execute(RequestHeaders.of(HttpMethod.PUT, path), content, charset);
    }

    /**
     * Sends an HTTP PUT request with the specified content, accepts query parameters to append to the path.
     */
    default AggregatedHttpResponse put(String path, QueryParams params, String content, Charset charset) {
        return execute(RequestHeaders.of(HttpMethod.PUT,
                                         WebClientUtil.addQueryParams(path, params)), content, charset);
    }

    /**
     * Sends an HTTP PATCH request with the specified content.
     */
    default AggregatedHttpResponse patch(String path, HttpData content) {
        return execute(RequestHeaders.of(HttpMethod.PATCH, path), content);
    }

    /**
     * Sends an HTTP PATCH request with the specified content by appending the provided
     * query params to the path.
     */
    default AggregatedHttpResponse patch(String path, QueryParams params, HttpData content) {
        return execute(RequestHeaders.of(HttpMethod.PATCH,
                                         WebClientUtil.addQueryParams(path, params)), content);
    }

    /**
     * Sends an HTTP PATCH request with the specified content.
     */
    default AggregatedHttpResponse patch(String path, byte[] content) {
        return execute(RequestHeaders.of(HttpMethod.PATCH, path), content);
    }

    /**
     * Sends an HTTP PATCH request with the specified content, accepts query parameters to append to the path.
     */
    default AggregatedHttpResponse patch(String path, QueryParams params, byte[] content) {
        return execute(RequestHeaders.of(HttpMethod.PATCH,
                                         WebClientUtil.addQueryParams(path, params)), content);
    }

    /**
     * Sends an HTTP PATCH request with the specified content.
     */
    default AggregatedHttpResponse patch(String path, String content) {
        return execute(RequestHeaders.of(HttpMethod.PATCH, path), HttpData.ofUtf8(content));
    }

    /**
     * Sends an HTTP PATCH request with the specified content, accepts query parameters to append to the path.
     */
    default AggregatedHttpResponse patch(String path, QueryParams params, String content) {
        return execute(RequestHeaders.of(HttpMethod.PATCH,
                                         WebClientUtil.addQueryParams(path, params)), content);
    }

    /**
     * Sends an HTTP PATCH request with the specified content.
     */
    default AggregatedHttpResponse patch(String path, String content, Charset charset) {
        return execute(RequestHeaders.of(HttpMethod.PATCH, path), content, charset);
    }

    /**
     * Sends an HTTP PATCH request with the specified content, accepts query parameters to append to the path.
     */
    default AggregatedHttpResponse patch(String path, QueryParams params, String content, Charset charset) {
        return execute(RequestHeaders.of(HttpMethod.PATCH,
                                         WebClientUtil.addQueryParams(path, params)), content, charset);
    }

    /**
     * Sends an HTTP DELETE request.
     */
    default AggregatedHttpResponse delete(String path) {
        return execute(RequestHeaders.of(HttpMethod.DELETE, path));
    }

    /**
     * Sends an HTTP DELETE request, accepts query parameters to append to the path.
     */
    default AggregatedHttpResponse delete(String path, QueryParams params) {
        return execute(RequestHeaders.of(HttpMethod.DELETE, WebClientUtil.addQueryParams(path, params)));
    }

    /**
     * Sends an HTTP TRACE request.
     */
    default AggregatedHttpResponse trace(String path) {
        return execute(RequestHeaders.of(HttpMethod.TRACE, path));
    }

    /**
     * Sends an HTTP TRACE request, accepts query parameters to append to the path.
     */
    default AggregatedHttpResponse trace(String path, QueryParams params) {
        return execute(RequestHeaders.of(HttpMethod.TRACE, WebClientUtil.addQueryParams(path, params)));
    }

    @Override
    HttpClient unwrap();
}
