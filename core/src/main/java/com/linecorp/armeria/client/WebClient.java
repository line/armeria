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

package com.linecorp.armeria.client;

import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.nio.charset.Charset;

import com.google.errorprone.annotations.CheckReturnValue;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.BlockingTaskExecutor;
import com.linecorp.armeria.common.util.Unwrappable;

import io.netty.channel.EventLoop;

/**
 * An asynchronous web client.
 */
public interface WebClient extends ClientBuilderParams, Unwrappable {

    /**
     * Returns a {@link WebClient} without a base URI using the default {@link ClientFactory} and
     * the default {@link ClientOptions}.
     */
    static WebClient of() {
        return DefaultWebClient.DEFAULT;
    }

    /**
     * Returns a new {@link WebClient} that connects to the specified {@code uri} using the default options.
     *
     * @param uri the URI of the server endpoint
     *
     * @throws IllegalArgumentException if the {@code uri} is not valid or its scheme is not one of the values
     *                                  in {@link SessionProtocol#httpValues()} or
     *                                  {@link SessionProtocol#httpsValues()}.
     */
    static WebClient of(String uri) {
        return builder(uri).build();
    }

    /**
     * Returns a new {@link WebClient} that connects to the specified {@link URI} using the default options.
     *
     * @param uri the {@link URI} of the server endpoint
     *
     * @throws IllegalArgumentException if the {@code uri} is not valid or its scheme is not one of the values
     *                                  in {@link SessionProtocol#httpValues()} or
     *                                  {@link SessionProtocol#httpsValues()}.
     */
    static WebClient of(URI uri) {
        return builder(uri).build();
    }

    /**
     * Returns a new {@link WebClient} that connects to the specified {@link EndpointGroup} with
     * the specified {@code protocol} using the default {@link ClientFactory} and the default
     * {@link ClientOptions}.
     *
     * @param protocol the session protocol of the {@link EndpointGroup}
     * @param endpointGroup the server {@link EndpointGroup}
     *
     * @throws IllegalArgumentException if the {@code protocol} is not one of the values in
     *                                  {@link SessionProtocol#httpValues()} or
     *                                  {@link SessionProtocol#httpsValues()}.
     */
    static WebClient of(String protocol, EndpointGroup endpointGroup) {
        return builder(protocol, endpointGroup).build();
    }

    /**
     * Returns a new {@link WebClient} that connects to the specified {@link EndpointGroup} with
     * the specified {@link SessionProtocol} using the default {@link ClientFactory} and the default
     * {@link ClientOptions}.
     *
     * @param protocol the {@link SessionProtocol} of the {@link EndpointGroup}
     * @param endpointGroup the server {@link EndpointGroup}
     *
     * @throws IllegalArgumentException if the {@code protocol} is not one of the values in
     *                                  {@link SessionProtocol#httpValues()} or
     *                                  {@link SessionProtocol#httpsValues()}.
     */
    static WebClient of(SessionProtocol protocol, EndpointGroup endpointGroup) {
        return builder(protocol, endpointGroup).build();
    }

    /**
     * Returns a new {@link WebClient} that connects to the specified {@link EndpointGroup} with
     * the specified {@code protocol} and {@code path} using the default {@link ClientFactory} and
     * the default {@link ClientOptions}.
     *
     * @param protocol the session protocol of the {@link EndpointGroup}
     * @param endpointGroup the server {@link EndpointGroup}
     * @param path the path to the endpoint
     *
     * @throws IllegalArgumentException if the {@code protocol} is not one of the values in
     *                                  {@link SessionProtocol#httpValues()} or
     *                                  {@link SessionProtocol#httpsValues()}.
     */
    static WebClient of(String protocol, EndpointGroup endpointGroup, String path) {
        return builder(protocol, endpointGroup, path).build();
    }

    /**
     * Returns a new {@link WebClient} that connects to the specified {@link EndpointGroup} with
     * the specified {@link SessionProtocol} and {@code path} using the default {@link ClientFactory} and
     * the default {@link ClientOptions}.
     *
     * @param protocol the {@link SessionProtocol} of the {@link EndpointGroup}
     * @param endpointGroup the server {@link EndpointGroup}
     * @param path the path to the endpoint
     *
     * @throws IllegalArgumentException if the {@code protocol} is not one of the values in
     *                                  {@link SessionProtocol#httpValues()} or
     *                                  {@link SessionProtocol#httpsValues()}.
     */
    static WebClient of(SessionProtocol protocol, EndpointGroup endpointGroup, String path) {
        return builder(protocol, endpointGroup, path).build();
    }

    /**
     * Returns a new {@link WebClientBuilder} created without a base {@link URI}.
     */
    static WebClientBuilder builder() {
        return new WebClientBuilder();
    }

    /**
     * Returns a new {@link WebClientBuilder} created with the specified base {@code uri}.
     *
     * @throws IllegalArgumentException if the {@code uri} is not valid or its scheme is not one of the values
     *                                  in {@link SessionProtocol#httpValues()} or
     *                                  {@link SessionProtocol#httpsValues()}.
     */
    static WebClientBuilder builder(String uri) {
        return builder(URI.create(requireNonNull(uri, "uri")));
    }

    /**
     * Returns a new {@link WebClientBuilder} created with the specified base {@link URI}.
     *
     * @throws IllegalArgumentException if the {@code uri} is not valid or its scheme is not one of the values
     *                                  in {@link SessionProtocol#httpValues()} or
     *                                  {@link SessionProtocol#httpsValues()}.
     */
    static WebClientBuilder builder(URI uri) {
        return new WebClientBuilder(uri);
    }

    /**
     * Returns a new {@link WebClientBuilder} created with the specified {@code protocol}
     * and base {@link EndpointGroup}.
     *
     * @throws IllegalArgumentException if the {@code protocol} is not one of the values in
     *                                  {@link SessionProtocol#httpValues()} or
     *                                  {@link SessionProtocol#httpsValues()}.
     */
    static WebClientBuilder builder(String protocol, EndpointGroup endpointGroup) {
        return builder(SessionProtocol.of(requireNonNull(protocol, "protocol")), endpointGroup);
    }

    /**
     * Returns a new {@link WebClientBuilder} created with the specified {@link SessionProtocol}
     * and base {@link EndpointGroup}.
     *
     * @throws IllegalArgumentException if the {@code protocol} is not one of the values in
     *                                  {@link SessionProtocol#httpValues()} or
     *                                  {@link SessionProtocol#httpsValues()}.
     */
    static WebClientBuilder builder(SessionProtocol protocol, EndpointGroup endpointGroup) {
        requireNonNull(protocol, "protocol");
        requireNonNull(endpointGroup, "endpointGroup");
        return new WebClientBuilder(protocol, endpointGroup, null);
    }

    /**
     * Returns a new {@link WebClientBuilder} created with the specified {@code protocol}.
     * base {@link EndpointGroup} and path.
     *
     * @throws IllegalArgumentException if the {@code protocol} is not one of the values in
     *                                  {@link SessionProtocol#httpValues()} or
     *                                  {@link SessionProtocol#httpsValues()}.
     */
    static WebClientBuilder builder(String protocol, EndpointGroup endpointGroup, String path) {
        requireNonNull(protocol, "protocol");
        requireNonNull(endpointGroup, "endpointGroup");
        requireNonNull(path, "path");
        return builder(SessionProtocol.of(protocol), endpointGroup, path);
    }

    /**
     * Returns a new {@link WebClientBuilder} created with the specified {@link SessionProtocol},
     * base {@link EndpointGroup} and path.
     *
     * @throws IllegalArgumentException if the {@code protocol} is not one of the values in
     *                                  {@link SessionProtocol#httpValues()} or
     *                                  {@link SessionProtocol#httpsValues()}.
     */
    static WebClientBuilder builder(SessionProtocol protocol, EndpointGroup endpointGroup, String path) {
        requireNonNull(protocol, "protocol");
        requireNonNull(endpointGroup, "endpointGroup");
        requireNonNull(path, "path");
        return new WebClientBuilder(protocol, endpointGroup, path);
    }

    /**
     * Sends the specified HTTP request.
     */
    @CheckReturnValue
    default HttpResponse execute(HttpRequest req) {
        return execute(req, RequestOptions.of());
    }

    /**
     * Sends the specified HTTP request with the specified {@link RequestOptions}.
     */
    @CheckReturnValue
    HttpResponse execute(HttpRequest req, RequestOptions options);

    /**
     * Sends the specified HTTP request.
     */
    @CheckReturnValue
    default HttpResponse execute(AggregatedHttpRequest aggregatedReq) {
        requireNonNull(aggregatedReq, "aggregatedReq");
        return execute(aggregatedReq.toHttpRequest());
    }

    /**
     * Sends an empty HTTP request with the specified headers.
     */
    @CheckReturnValue
    default HttpResponse execute(RequestHeaders headers) {
        return execute(HttpRequest.of(headers));
    }

    /**
     * Sends an HTTP request with the specified headers and content.
     */
    @CheckReturnValue
    default HttpResponse execute(RequestHeaders headers, HttpData content) {
        return execute(HttpRequest.of(headers, content));
    }

    /**
     * Sends an HTTP request with the specified headers and content.
     */
    @CheckReturnValue
    default HttpResponse execute(RequestHeaders headers, byte[] content) {
        return execute(HttpRequest.of(headers, HttpData.wrap(content)));
    }

    /**
     * Sends an HTTP request with the specified headers and content.
     */
    @CheckReturnValue
    default HttpResponse execute(RequestHeaders headers, String content) {
        return execute(HttpRequest.of(headers, HttpData.ofUtf8(content)));
    }

    /**
     * Sends an HTTP request with the specified headers and content.
     */
    @CheckReturnValue
    default HttpResponse execute(RequestHeaders headers, String content, Charset charset) {
        return execute(HttpRequest.of(headers, HttpData.of(charset, content)));
    }

    /**
     * Prepares to send an {@link HttpRequest} using fluent builder.
     * <pre>{@code
     * WebClient client = WebClient.of(...);
     * HttpResponse response = client.prepare()
     *                               .post("/foo")
     *                               .header(HttpHeaderNames.AUTHORIZATION, ...)
     *                               .content(MediaType.JSON, ...)
     *                               .execute();
     * }</pre>
     */
    default WebClientRequestPreparation prepare() {
        return new WebClientRequestPreparation(this);
    }

    /**
     * Sends an HTTP OPTIONS request.
     */
    @CheckReturnValue
    default HttpResponse options(String path) {
        return options(path, null);
    }

    /**
     * Sends an HTTP OPTIONS request, appending the given query parameters to the path.
     */
    @CheckReturnValue
    default HttpResponse options(String path, @Nullable QueryParams params) {
        return execute(RequestHeaders.of(HttpMethod.OPTIONS, WebClientUtil.addQueryParams(path, params)));
    }

    /**
     * Sends an HTTP GET request.
     */
    @CheckReturnValue
    default HttpResponse get(String path) {
        return get(path, null);
    }

    /**
     * Sends an HTTP GET request, appending the given query parameters to the path.
     */
    @CheckReturnValue
    default HttpResponse get(String path, @Nullable QueryParams params) {
        return execute(RequestHeaders.of(HttpMethod.GET, WebClientUtil.addQueryParams(path, params)));
    }

    /**
     * Sends an HTTP HEAD request.
     */
    @CheckReturnValue
    default HttpResponse head(String path) {
        return head(path, null);
    }

    /**
     * Sends an HTTP HEAD request, appending the given query parameters to the path.
     */
    @CheckReturnValue
    default HttpResponse head(String path, @Nullable QueryParams params) {
        return execute(RequestHeaders.of(HttpMethod.HEAD, WebClientUtil.addQueryParams(path, params)));
    }

    /**
     * Sends an HTTP POST request with the specified content.
     */
    @CheckReturnValue
    default HttpResponse post(String path, HttpData content) {
        return post(path, null, content);
    }

    /**
     * Sends an HTTP POST request with the specified content, appending the given query parameters to the path.
     */
    @CheckReturnValue
    default HttpResponse post(String path, @Nullable QueryParams params, HttpData content) {
        return execute(RequestHeaders.of(HttpMethod.POST, WebClientUtil.addQueryParams(path, params)), content);
    }

    /**
     * Sends an HTTP POST request with the specified content.
     */
    @CheckReturnValue
    default HttpResponse post(String path, byte[] content) {
        return post(path, null, content);
    }

    /**
     * Sends an HTTP POST request with the specified content, appending the given query parameters to the path.
     */
    @CheckReturnValue
    default HttpResponse post(String path, @Nullable QueryParams params, byte[] content) {
        return execute(RequestHeaders.of(HttpMethod.POST, WebClientUtil.addQueryParams(path, params)), content);
    }

    /**
     * Sends an HTTP POST request with the specified content.
     */
    @CheckReturnValue
    default HttpResponse post(String path, String content) {
        return post(path, null, content);
    }

    /**
     * Sends an HTTP POST request with the specified content, appending the given query parameters to the path.
     */
    @CheckReturnValue
    default HttpResponse post(String path, @Nullable QueryParams params, String content) {
        return execute(RequestHeaders.of(HttpMethod.POST, WebClientUtil.addQueryParams(path, params)), content);
    }

    /**
     * Sends an HTTP POST request with the specified content.
     */
    @CheckReturnValue
    default HttpResponse post(String path, String content, Charset charset) {
        return post(path, null, content, charset);
    }

    /**
     * Sends an HTTP POST request with the specified content, appending the given query parameters to the path.
     */
    @CheckReturnValue
    default HttpResponse post(String path, @Nullable QueryParams params, String content, Charset charset) {
        return execute(RequestHeaders.of(HttpMethod.POST,
                                         WebClientUtil.addQueryParams(path, params)), content, charset);
    }

    /**
     * Sends an HTTP PUT request with the specified content.
     */
    @CheckReturnValue
    default HttpResponse put(String path, HttpData content) {
        return put(path, null, content);
    }

    /**
     * Sends an HTTP PUT request with the specified content, appending the given query parameters to the path.
     */
    @CheckReturnValue
    default HttpResponse put(String path, @Nullable QueryParams params, HttpData content) {
        return execute(RequestHeaders.of(HttpMethod.PUT,
                                         WebClientUtil.addQueryParams(path, params)), content);
    }

    /**
     * Sends an HTTP PUT request with the specified content.
     */
    @CheckReturnValue
    default HttpResponse put(String path, byte[] content) {
        return put(path, null, content);
    }

    /**
     * Sends an HTTP PUT request with the specified content, appending the given query parameters to the path.
     */
    @CheckReturnValue
    default HttpResponse put(String path, @Nullable QueryParams params, byte[] content) {
        return execute(RequestHeaders.of(HttpMethod.PUT,
                                         WebClientUtil.addQueryParams(path, params)), content);
    }

    /**
     * Sends an HTTP PUT request with the specified content.
     */
    @CheckReturnValue
    default HttpResponse put(String path, String content) {
        return put(path, null, content);
    }

    /**
     * Sends an HTTP PUT request with the specified content, appending the given query parameters to the path.
     */
    @CheckReturnValue
    default HttpResponse put(String path, @Nullable QueryParams params, String content) {
        return execute(RequestHeaders.of(HttpMethod.PUT,
                                         WebClientUtil.addQueryParams(path, params)), content);
    }

    /**
     * Sends an HTTP PUT request with the specified content.
     */
    @CheckReturnValue
    default HttpResponse put(String path, String content, Charset charset) {
        return put(path, null, content, charset);
    }

    /**
     * Sends an HTTP PUT request with the specified content, appending the given query parameters to the path.
     */
    @CheckReturnValue
    default HttpResponse put(String path, @Nullable QueryParams params, String content, Charset charset) {
        return execute(RequestHeaders.of(HttpMethod.PUT,
                                         WebClientUtil.addQueryParams(path, params)), content, charset);
    }

    /**
     * Sends an HTTP PATCH request with the specified content.
     */
    @CheckReturnValue
    default HttpResponse patch(String path, HttpData content) {
        return patch(path, null, content);
    }

    /**
     * Sends an HTTP PATCH request with the specified content by appending the provided
     * query params to the path.
     */
    @CheckReturnValue
    default HttpResponse patch(String path, @Nullable QueryParams params, HttpData content) {
        return execute(RequestHeaders.of(HttpMethod.PATCH,
                                         WebClientUtil.addQueryParams(path, params)), content);
    }

    /**
     * Sends an HTTP PATCH request with the specified content.
     */
    @CheckReturnValue
    default HttpResponse patch(String path, byte[] content) {
        return patch(path, null, content);
    }

    /**
     * Sends an HTTP PATCH request with the specified content, appending the given query parameters to the path.
     */
    @CheckReturnValue
    default HttpResponse patch(String path, @Nullable QueryParams params, byte[] content) {
        return execute(RequestHeaders.of(HttpMethod.PATCH,
                                         WebClientUtil.addQueryParams(path, params)), content);
    }

    /**
     * Sends an HTTP PATCH request with the specified content.
     */
    @CheckReturnValue
    default HttpResponse patch(String path, String content) {
        return patch(path, null, content);
    }

    /**
     * Sends an HTTP PATCH request with the specified content, appending the given query parameters to the path.
     */
    @CheckReturnValue
    default HttpResponse patch(String path, @Nullable QueryParams params, String content) {
        return execute(RequestHeaders.of(HttpMethod.PATCH,
                                         WebClientUtil.addQueryParams(path, params)), content);
    }

    /**
     * Sends an HTTP PATCH request with the specified content.
     */
    @CheckReturnValue
    default HttpResponse patch(String path, String content, Charset charset) {
        return patch(path, null, content, charset);
    }

    /**
     * Sends an HTTP PATCH request with the specified content, appending the given query parameters to the path.
     */
    @CheckReturnValue
    default HttpResponse patch(String path, @Nullable QueryParams params, String content, Charset charset) {
        return execute(RequestHeaders.of(HttpMethod.PATCH,
                                         WebClientUtil.addQueryParams(path, params)), content, charset);
    }

    /**
     * Sends an HTTP DELETE request.
     */
    @CheckReturnValue
    default HttpResponse delete(String path) {
        return delete(path, null);
    }

    /**
     * Sends an HTTP DELETE request, appending the given query parameters to the path.
     */
    @CheckReturnValue
    default HttpResponse delete(String path, @Nullable QueryParams params) {
        return execute(RequestHeaders.of(HttpMethod.DELETE, WebClientUtil.addQueryParams(path, params)));
    }

    /**
     * Sends an HTTP TRACE request.
     */
    @CheckReturnValue
    default HttpResponse trace(String path) {
        return trace(path, null);
    }

    /**
     * Sends an HTTP TRACE request, appending the given query parameters to the path.
     */
    @CheckReturnValue
    default HttpResponse trace(String path, @Nullable QueryParams params) {
        return execute(RequestHeaders.of(HttpMethod.TRACE, WebClientUtil.addQueryParams(path, params)));
    }

    /**
     * Returns a {@link BlockingWebClient} that connects to the same {@link URI} with this {@link WebClient}.
     * Note that a blocking request should be sent in a non-{@link EventLoop} thread such as a
     * {@link BlockingTaskExecutor}.
     */
    @UnstableApi
    BlockingWebClient blocking();

    @Override
    HttpClient unwrap();
}
