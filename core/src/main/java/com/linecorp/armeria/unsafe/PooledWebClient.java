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

package com.linecorp.armeria.unsafe;

import java.nio.charset.Charset;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestHeaders;

/**
 * An asynchronous web client which will return pooled buffers in its responses where possible. Before using
 * this class, please review the notes in {@link PooledHttpData}. Usage of this class is very advanced and while
 * it may unlock significant performance, has even more of a chance of introducing difficult to debug issues in
 * an application. If you are not comfortable with this, use {@link WebClient}.
 */
public interface PooledWebClient extends WebClient {

    /**
     * Creates a {@link PooledWebClient} that delegates to the provided {@link HttpClient} for issuing requests.
     */
    static PooledWebClient of(WebClient delegate) {
        return new DefaultPooledWebClient(delegate);
    }

    /**
     * Sends the specified HTTP request.
     */
    PooledHttpResponse execute(HttpRequest req);

    /**
     * Sends the specified HTTP request.
     */
    PooledHttpResponse execute(AggregatedHttpRequest aggregatedReq);

    /**
     * Sends an empty HTTP request with the specified headers.
     */
    default PooledHttpResponse execute(RequestHeaders headers) {
        return execute(HttpRequest.of(headers));
    }

    /**
     * Sends an HTTP request with the specified headers and content.
     */
    default PooledHttpResponse execute(RequestHeaders headers, HttpData content) {
        return execute(HttpRequest.of(headers, content));
    }

    /**
     * Sends an HTTP request with the specified headers and content.
     */
    default PooledHttpResponse execute(RequestHeaders headers, byte[] content) {
        return execute(HttpRequest.of(headers, HttpData.wrap(content)));
    }

    /**
     * Sends an HTTP request with the specified headers and content.
     */
    default PooledHttpResponse execute(RequestHeaders headers, String content) {
        return execute(HttpRequest.of(headers, HttpData.ofUtf8(content)));
    }

    /**
     * Sends an HTTP request with the specified headers and content.
     */
    default PooledHttpResponse execute(RequestHeaders headers, String content, Charset charset) {
        return execute(HttpRequest.of(headers, HttpData.of(charset, content)));
    }

    /**
     * Sends an HTTP OPTIONS request.
     */
    default PooledHttpResponse options(String path) {
        return execute(RequestHeaders.of(HttpMethod.OPTIONS, path));
    }

    /**
     * Sends an HTTP GET request.
     */
    default PooledHttpResponse get(String path) {
        return execute(RequestHeaders.of(HttpMethod.GET, path));
    }

    /**
     * Sends an HTTP HEAD request.
     */
    default PooledHttpResponse head(String path) {
        return execute(RequestHeaders.of(HttpMethod.HEAD, path));
    }

    /**
     * Sends an HTTP POST request with the specified content.
     */
    default PooledHttpResponse post(String path, HttpData content) {
        return execute(RequestHeaders.of(HttpMethod.POST, path), content);
    }

    /**
     * Sends an HTTP POST request with the specified content.
     */
    default PooledHttpResponse post(String path, byte[] content) {
        return execute(RequestHeaders.of(HttpMethod.POST, path), content);
    }

    /**
     * Sends an HTTP POST request with the specified content.
     */
    default PooledHttpResponse post(String path, String content) {
        return execute(RequestHeaders.of(HttpMethod.POST, path), HttpData.ofUtf8(content));
    }

    /**
     * Sends an HTTP POST request with the specified content.
     */
    default PooledHttpResponse post(String path, String content, Charset charset) {
        return execute(RequestHeaders.of(HttpMethod.POST, path), content, charset);
    }

    /**
     * Sends an HTTP PUT request with the specified content.
     */
    default PooledHttpResponse put(String path, HttpData content) {
        return execute(RequestHeaders.of(HttpMethod.PUT, path), content);
    }

    /**
     * Sends an HTTP PUT request with the specified content.
     */
    default PooledHttpResponse put(String path, byte[] content) {
        return execute(RequestHeaders.of(HttpMethod.PUT, path), content);
    }

    /**
     * Sends an HTTP PUT request with the specified content.
     */
    default PooledHttpResponse put(String path, String content) {
        return execute(RequestHeaders.of(HttpMethod.PUT, path), HttpData.ofUtf8(content));
    }

    /**
     * Sends an HTTP PUT request with the specified content.
     */
    default PooledHttpResponse put(String path, String content, Charset charset) {
        return execute(RequestHeaders.of(HttpMethod.PUT, path), content, charset);
    }

    /**
     * Sends an HTTP PATCH request with the specified content.
     */
    default PooledHttpResponse patch(String path, HttpData content) {
        return execute(RequestHeaders.of(HttpMethod.PATCH, path), content);
    }

    /**
     * Sends an HTTP PATCH request with the specified content.
     */
    default PooledHttpResponse patch(String path, byte[] content) {
        return execute(RequestHeaders.of(HttpMethod.PATCH, path), content);
    }

    /**
     * Sends an HTTP PATCH request with the specified content.
     */
    default PooledHttpResponse patch(String path, String content) {
        return execute(RequestHeaders.of(HttpMethod.PATCH, path), HttpData.ofUtf8(content));
    }

    /**
     * Sends an HTTP PATCH request with the specified content.
     */
    default PooledHttpResponse patch(String path, String content, Charset charset) {
        return execute(RequestHeaders.of(HttpMethod.PATCH, path), content, charset);
    }

    /**
     * Sends an HTTP DELETE request.
     */
    default PooledHttpResponse delete(String path) {
        return execute(RequestHeaders.of(HttpMethod.DELETE, path));
    }

    /**
     * Sends an HTTP TRACE request.
     */
    default PooledHttpResponse trace(String path) {
        return execute(RequestHeaders.of(HttpMethod.TRACE, path));
    }
}
