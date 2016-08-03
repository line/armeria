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

package com.linecorp.armeria.client.http;

import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;

import com.linecorp.armeria.common.http.AggregatedHttpMessage;
import com.linecorp.armeria.common.http.HttpRequest;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.EmptyHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;

/**
 * Creates a new {@link SimpleHttpRequest}.
 *
 * @deprecated Use {@link AggregatedHttpMessage} instead.
 */
@Deprecated
public class SimpleHttpRequestBuilder {

    private static final byte[] EMPTY = new byte[0];

    private final URI uri;
    private final HttpMethod method;
    private HttpHeaders headers = EmptyHttpHeaders.INSTANCE;
    private byte[] content = EMPTY;

    /**
     * Returns a {@link SimpleHttpRequestBuilder} for a GET request to the given URI,
     * for setting additional HTTP parameters as needed.
     */
    public static SimpleHttpRequestBuilder forGet(String uri) {
        return createRequestBuilder(uri, HttpMethod.GET);
    }

    /**
     * Returns a {@link SimpleHttpRequestBuilder} for a POST request to the given URI,
     * for setting additional HTTP parameters as needed.
     */
    public static SimpleHttpRequestBuilder forPost(String uri) {
        return createRequestBuilder(uri, HttpMethod.POST);
    }

    /**
     * Returns a {@link SimpleHttpRequestBuilder} for a PUT request to the given URI,
     * for setting additional HTTP parameters as needed.
     */
    public static SimpleHttpRequestBuilder forPut(String uri) {
        return createRequestBuilder(uri, HttpMethod.PUT);
    }

    /**
     * Returns a {@link SimpleHttpRequestBuilder} for a PATCH request to the given URI,
     * for setting additional HTTP parameters as needed.
     */
    public static SimpleHttpRequestBuilder forPatch(String uri) {
        return createRequestBuilder(uri, HttpMethod.PATCH);
    }

    /**
     * Returns a {@link SimpleHttpRequestBuilder} for a DELETE request to the given URI,
     * for setting additional HTTP parameters as needed.
     */
    public static SimpleHttpRequestBuilder forDelete(String uri) {
        return createRequestBuilder(uri, HttpMethod.DELETE);
    }

    /**
     * Returns a {@link SimpleHttpRequestBuilder} for a HEAD request to the given URI,
     * for setting additional HTTP parameters as needed.
     */
    public static SimpleHttpRequestBuilder forHead(String uri) {
        return createRequestBuilder(uri, HttpMethod.HEAD);
    }

    /**
     * Returns a {@link SimpleHttpRequestBuilder} for an OPTIONS request to the given URI,
     * for setting additional HTTP parameters as needed.
     */
    public static SimpleHttpRequestBuilder forOptions(String uri) {
        return createRequestBuilder(uri, HttpMethod.OPTIONS);
    }

    private static SimpleHttpRequestBuilder createRequestBuilder(String uri, HttpMethod method) {
        requireNonNull(uri);
        try {
            return new SimpleHttpRequestBuilder(new URI(uri), method);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("invalid uri: " + uri, e);
        }
    }

    private SimpleHttpRequestBuilder(URI uri, HttpMethod method) {
        this.uri = uri;
        this.method = method;
    }

    /**
     * Adds a new header with the specified {@code name} and {@code value}.
     */
    public SimpleHttpRequestBuilder header(CharSequence name, Object value) {
        headers().add(requireNonNull(name, "name"), requireNonNull(value, "value"));
        return this;
    }

    /**
     * Adds all header entries in the specified {@code headers}.
     */
    public SimpleHttpRequestBuilder headers(HttpHeaders headers) {
        headers().add(requireNonNull(headers));
        return this;
    }

    private HttpHeaders headers() {
        if (headers == EmptyHttpHeaders.INSTANCE) {
            headers = new DefaultHttpHeaders();
        }
        return headers;
    }

    /**
     * Sets the given bytes to be used as the request content.
     */
    public SimpleHttpRequestBuilder content(byte[] bytes) {
        this.content = bytes;
        return this;
    }

    /**
     * Sets the given string to be used as the request content, decoding to bytes with the
     * given charset.
     */
    public SimpleHttpRequestBuilder content(String chars, Charset charset) {
        return content(chars.getBytes(charset));
    }

    /**
     * Creates a new {@link SimpleHttpRequest}.
     */
    public SimpleHttpRequest build() {
        return new SimpleHttpRequest(uri, method, headers, content);
    }

    @Override
    public String toString() {
        return SimpleHttpRequest.toString(uri, method, headers, content);
    }
}
