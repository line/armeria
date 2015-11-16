/*
 * Copyright 2015 LINE Corporation
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

import java.net.URI;

import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;

/**
 * A container for information to send in an HTTP request. This is a simpler version of {@link FullHttpRequest}
 * which only uses a byte array to avoid callers having to worry about memory management.
 */
public class SimpleHttpRequest {

    private final URI uri;
    private final HttpMethod method;
    private final HttpHeaders headers;
    private final byte[] content;

    SimpleHttpRequest(URI uri, HttpMethod method, HttpHeaders headers,
                      byte[] content) {
        this.uri = uri;
        this.method = method;
        this.headers = new ImmutableHttpHeaders(headers);
        this.content = content;
    }

    /**
     * Returns this request's URI.
     */
    public URI uri() {
        return uri;
    }

    /**
     * Returns this request's HTTP method.
     */
    public HttpMethod method() {
        return method;
    }

    /**
     * Returns this request's HTTP headers.
     */
    public HttpHeaders headers() {
        return headers;
    }

    /**
     * Returns the length of this requests's content.
     */
    public int contentLength() {
        return content.length;
    }

    /**
     * Reads this request's content into the destination buffer.
     */
    public void readContent(byte[] dst, int offset, int length) {
        System.arraycopy(content, 0, dst, offset, length);
    }

    byte[] content() {
        return content;
    }

    @Override
    public String toString() {
        return toString(uri, method, headers, content);
    }

    static String toString(URI uri, HttpMethod method, HttpHeaders headers,
                           byte[] content) {
        StringBuilder buf = new StringBuilder();
        buf.append('(');
        buf.append("uri: ").append(uri);
        buf.append(", method: ").append(method);
        buf.append(", headers: ").append(headers);
        buf.append(", content: ");
        if (content.length > 0) {
            buf.append("<length: ").append(content.length).append('>');
        } else {
            buf.append("<none>");
        }
        buf.append(')');
        return buf.toString();
    }
}
