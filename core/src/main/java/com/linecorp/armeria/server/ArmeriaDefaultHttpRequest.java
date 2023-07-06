/*
 * Copyright 2023 LINE Corporation
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
package com.linecorp.armeria.server;

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.Map;

import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestHeadersBuilder;

import io.netty.handler.codec.http.DefaultHttpMessage;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.internal.ObjectUtil;

final class ArmeriaDefaultHttpRequest extends DefaultHttpMessage implements HttpRequest {
    private static final int HASH_CODE_PRIME = 31;
    private HttpMethod method;
    private final RequestHeadersBuilder builder;
    private final HttpHeaders headers;
    private String uri;

    /**
     * Creates a new instance.
     *
     * @param httpVersion       the HTTP version of the request
     * @param method            the HTTP method of the request
     * @param uri               the URI or path of the request
     * @param validateHeaders   validate the header names and values when adding them to the {@link HttpHeaders}
     */
    ArmeriaDefaultHttpRequest(HttpVersion httpVersion, HttpMethod method, String uri,
                                     boolean validateHeaders) {
        super(httpVersion, validateHeaders, false);
        this.method = checkNotNull(method, "method");
        this.uri = checkNotNull(uri, "uri");
        final com.linecorp.armeria.common.HttpMethod armeriaMethod =
                com.linecorp.armeria.common.HttpMethod.tryParse(method.name());
        builder = RequestHeaders.builder(armeriaMethod != null ? armeriaMethod
                                                               : com.linecorp.armeria.common.HttpMethod.UNKNOWN,
                                         uri);
        headers = new ArmeriaHttpHeaders(builder);
    }

    @Override
    public HttpHeaders headers() {
        return headers;
    }

    @Override
    @Deprecated
    public HttpMethod getMethod() {
        return method();
    }

    @Override
    public HttpMethod method() {
        return method;
    }

    @Override
    @Deprecated
    public String getUri() {
        return uri();
    }

    @Override
    public String uri() {
        return uri;
    }

    public RequestHeadersBuilder requestHeadersBuilder() {
        return builder;
    }

    @Override
    public HttpRequest setMethod(HttpMethod method) {
        this.method = ObjectUtil.checkNotNull(method, "method");
        return this;
    }

    @Override
    public HttpRequest setUri(String uri) {
        this.uri = ObjectUtil.checkNotNull(uri, "uri");
        return this;
    }

    @Override
    public HttpRequest setProtocolVersion(HttpVersion version) {
        super.setProtocolVersion(version);
        return this;
    }

    @Override
    public int hashCode() {
        int result = 1;
        result = HASH_CODE_PRIME * result + method.hashCode();
        result = HASH_CODE_PRIME * result + uri.hashCode();
        result = HASH_CODE_PRIME * result + super.hashCode();
        return result;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ArmeriaDefaultHttpRequest)) {
            return false;
        }

        final ArmeriaDefaultHttpRequest other = (ArmeriaDefaultHttpRequest) o;

        return method().equals(other.method()) &&
               uri().equalsIgnoreCase(other.uri()) &&
               super.equals(o);
    }

    @Override
    public String toString() {
        final ToStringHelper toStringHelper = MoreObjects.toStringHelper(this)
                                                         .add("decoderResult", decoderResult())
                                                         .add("version", protocolVersion())
                                                         .add("method", method());

        for (Map.Entry<String, String> e: headers()) {
            toStringHelper.add(e.getKey(), e.getValue());
        }

        return toStringHelper.toString();
    }
}
