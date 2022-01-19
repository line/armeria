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

import java.net.URI;
import java.nio.charset.Charset;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.BlockingTaskExecutor;
import com.linecorp.armeria.common.util.Unwrappable;

import io.netty.channel.EventLoop;

/**
 * A blocking web client that waits for a {@link HttpRequest} to be fully aggregated.
 * If you want to create a {@link BlockingWebClient} with various options, create a {@link WebClient} first
 * and convert it into a {@link BlockingWebClient} via {@link WebClient#blocking()}.
 * <pre>{@code
 * BlockingWebClient client =
 *     WebClient.builder("https://api.example.com")
 *              .responseTimeout(Duration.ofSeconds(10))
 *              .decorator(LoggingClient.newDecorator())
 *              ...
 *              .build()
 *              .blocking();
 * }</pre>
 *
 * <p>Note that you should never use this client in an {@link EventLoop} thread.
 * Use it from a non-{@link EventLoop} thread such as {@link BlockingTaskExecutor}.
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
     * Returns a new {@link BlockingWebClient} that connects to the specified {@code uri} using the default
     * options.
     *
     * @param uri the URI of the server endpoint
     *
     * @throws IllegalArgumentException if the {@code uri} is not valid or its scheme is not one of the values
     *                                  in {@link SessionProtocol#httpValues()} or
     *                                  {@link SessionProtocol#httpsValues()}.
     */
    static BlockingWebClient of(String uri) {
        return WebClient.builder(uri).build().blocking();
    }

    /**
     * Returns a new {@link BlockingWebClient} that connects to the specified {@link URI} using the default
     * options.
     *
     * @param uri the URI of the server endpoint
     *
     * @throws IllegalArgumentException if the {@code uri} is not valid or its scheme is not one of the values
     *                                  in {@link SessionProtocol#httpValues()} or
     *                                  {@link SessionProtocol#httpsValues()}.
     */
    static BlockingWebClient of(URI uri) {
        return WebClient.builder(uri).build().blocking();
    }

    /**
     * Sends the specified HTTP request.
     */
    default AggregatedHttpResponse execute(HttpRequest req) {
        return execute(req, RequestOptions.of());
    }

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
        return options(path, null);
    }

    /**
     * Sends an HTTP OPTIONS request, appending the given query parameters to the path.
     */
    default AggregatedHttpResponse options(String path, @Nullable QueryParams params) {
        return execute(RequestHeaders.of(HttpMethod.OPTIONS, WebClientUtil.addQueryParams(path, params)));
    }

    /**
     * Sends an HTTP GET request.
     */
    default AggregatedHttpResponse get(String path) {
        return get(path, null);
    }

    /**
     * Sends an HTTP GET request, appending the given query parameters to the path.
     */
    default AggregatedHttpResponse get(String path, @Nullable QueryParams params) {
        return execute(RequestHeaders.of(HttpMethod.GET, WebClientUtil.addQueryParams(path, params)));
    }

    /**
     * Sends an HTTP HEAD request.
     */
    default AggregatedHttpResponse head(String path) {
        return head(path, null);
    }

    /**
     * Sends an HTTP HEAD request, appending the given query parameters to the path.
     */
    default AggregatedHttpResponse head(String path, @Nullable QueryParams params) {
        return execute(RequestHeaders.of(HttpMethod.HEAD, WebClientUtil.addQueryParams(path, params)));
    }

    /**
     * Sends an HTTP POST request with the specified content.
     */
    default AggregatedHttpResponse post(String path, HttpData content) {
        return post(path, null, content);
    }

    /**
     * Sends an HTTP POST request with the specified content, appending the given query parameters to the path.
     */
    default AggregatedHttpResponse post(String path, @Nullable QueryParams params, HttpData content) {
        return execute(RequestHeaders.of(HttpMethod.POST, WebClientUtil.addQueryParams(path, params)), content);
    }

    /**
     * Sends an HTTP POST request with the specified content.
     */
    default AggregatedHttpResponse post(String path, byte[] content) {
        return post(path, null, content);
    }

    /**
     * Sends an HTTP POST request with the specified content, appending the given query parameters to the path.
     */
    default AggregatedHttpResponse post(String path, @Nullable QueryParams params, byte[] content) {
        return execute(RequestHeaders.of(HttpMethod.POST, WebClientUtil.addQueryParams(path, params)), content);
    }

    /**
     * Sends an HTTP POST request with the specified content.
     */
    default AggregatedHttpResponse post(String path, String content) {
        return post(path, null, content);
    }

    /**
     * Sends an HTTP POST request with the specified content, appending the given query parameters to the path.
     */
    default AggregatedHttpResponse post(String path, @Nullable QueryParams params, String content) {
        return execute(RequestHeaders.of(HttpMethod.POST, WebClientUtil.addQueryParams(path, params)), content);
    }

    /**
     * Sends an HTTP POST request with the specified content.
     */
    default AggregatedHttpResponse post(String path, String content, Charset charset) {
        return post(path, null, content, charset);
    }

    /**
     * Sends an HTTP POST request with the specified content, appending the given query parameters to the path.
     */
    default AggregatedHttpResponse post(String path, @Nullable QueryParams params,
                                        String content, Charset charset) {
        return execute(RequestHeaders.of(HttpMethod.POST,
                                         WebClientUtil.addQueryParams(path, params)), content, charset);
    }

    /**
     * Sends an HTTP PUT request with the specified content.
     */
    default AggregatedHttpResponse put(String path, HttpData content) {
        return put(path, null, content);
    }

    /**
     * Sends an HTTP PUT request with the specified content, appending the given query parameters to the path.
     */
    default AggregatedHttpResponse put(String path, @Nullable QueryParams params, HttpData content) {
        return execute(RequestHeaders.of(HttpMethod.PUT,
                                         WebClientUtil.addQueryParams(path, params)), content);
    }

    /**
     * Sends an HTTP PUT request with the specified content.
     */
    default AggregatedHttpResponse put(String path, byte[] content) {
        return put(path, null, content);
    }

    /**
     * Sends an HTTP PUT request with the specified content, appending the given query parameters to the path.
     */
    default AggregatedHttpResponse put(String path, @Nullable QueryParams params, byte[] content) {
        return execute(RequestHeaders.of(HttpMethod.PUT,
                                         WebClientUtil.addQueryParams(path, params)), content);
    }

    /**
     * Sends an HTTP PUT request with the specified content.
     */
    default AggregatedHttpResponse put(String path, String content) {
        return put(path, null, content);
    }

    /**
     * Sends an HTTP PUT request with the specified content, appending the given query parameters to the path.
     */
    default AggregatedHttpResponse put(String path, @Nullable QueryParams params, String content) {
        return execute(RequestHeaders.of(HttpMethod.PUT,
                                         WebClientUtil.addQueryParams(path, params)), content);
    }

    /**
     * Sends an HTTP PUT request with the specified content.
     */
    default AggregatedHttpResponse put(String path, String content, Charset charset) {
        return put(path, null, content, charset);
    }

    /**
     * Sends an HTTP PUT request with the specified content, appending the given query parameters to the path.
     */
    default AggregatedHttpResponse put(String path, @Nullable QueryParams params, String content,
                                       Charset charset) {
        return execute(RequestHeaders.of(HttpMethod.PUT,
                                         WebClientUtil.addQueryParams(path, params)), content, charset);
    }

    /**
     * Sends an HTTP PATCH request with the specified content.
     */
    default AggregatedHttpResponse patch(String path, HttpData content) {
        return patch(path, null, content);
    }

    /**
     * Sends an HTTP PATCH request with the specified content by appending the provided
     * query params to the path.
     */
    default AggregatedHttpResponse patch(String path, @Nullable QueryParams params, HttpData content) {
        return execute(RequestHeaders.of(HttpMethod.PATCH,
                                         WebClientUtil.addQueryParams(path, params)), content);
    }

    /**
     * Sends an HTTP PATCH request with the specified content.
     */
    default AggregatedHttpResponse patch(String path, byte[] content) {
        return patch(path, null, content);
    }

    /**
     * Sends an HTTP PATCH request with the specified content, appending the given query parameters to the path.
     */
    default AggregatedHttpResponse patch(String path, @Nullable QueryParams params, byte[] content) {
        return execute(RequestHeaders.of(HttpMethod.PATCH,
                                         WebClientUtil.addQueryParams(path, params)), content);
    }

    /**
     * Sends an HTTP PATCH request with the specified content.
     */
    default AggregatedHttpResponse patch(String path, String content) {
        return patch(path, null, content);
    }

    /**
     * Sends an HTTP PATCH request with the specified content, appending the given query parameters to the path.
     */
    default AggregatedHttpResponse patch(String path, @Nullable QueryParams params, String content) {
        return execute(RequestHeaders.of(HttpMethod.PATCH,
                                         WebClientUtil.addQueryParams(path, params)), content);
    }

    /**
     * Sends an HTTP PATCH request with the specified content.
     */
    default AggregatedHttpResponse patch(String path, String content, Charset charset) {
        return patch(path, null, content, charset);
    }

    /**
     * Sends an HTTP PATCH request with the specified content, appending the given query parameters to the path.
     */
    default AggregatedHttpResponse patch(String path, @Nullable QueryParams params, String content,
                                         Charset charset) {
        return execute(RequestHeaders.of(HttpMethod.PATCH,
                                         WebClientUtil.addQueryParams(path, params)), content, charset);
    }

    /**
     * Sends an HTTP DELETE request.
     */
    default AggregatedHttpResponse delete(String path) {
        return delete(path, null);
    }

    /**
     * Sends an HTTP DELETE request, appending the given query parameters to the path.
     */
    default AggregatedHttpResponse delete(String path, @Nullable QueryParams params) {
        return execute(RequestHeaders.of(HttpMethod.DELETE, WebClientUtil.addQueryParams(path, params)));
    }

    /**
     * Sends an HTTP TRACE request.
     */
    default AggregatedHttpResponse trace(String path) {
        return trace(path, null);
    }

    /**
     * Sends an HTTP TRACE request, appending the given query parameters to the path.
     */
    default AggregatedHttpResponse trace(String path, @Nullable QueryParams params) {
        return execute(RequestHeaders.of(HttpMethod.TRACE, WebClientUtil.addQueryParams(path, params)));
    }

    @Override
    HttpClient unwrap();
}
