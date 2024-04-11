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

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.linecorp.armeria.common.HttpMethod.UNKNOWN;

import java.util.Objects;

import com.google.common.base.MoreObjects;

import io.netty.handler.codec.http.DefaultHttpMessage;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.internal.ObjectUtil;

final class NettyHttp1Request extends DefaultHttpMessage implements HttpRequest {

    private HttpMethod method;
    private String uri;

    NettyHttp1Request(HttpVersion httpVersion, HttpMethod method, String uri) {
        super(httpVersion, ArmeriaHttpHeadersFactory.INSTANCE);
        this.method = checkNotNull(method, "method");
        this.uri = checkNotNull(uri, "uri");
        headers().delegate().method(
                firstNonNull(com.linecorp.armeria.common.HttpMethod.tryParse(method.name()), UNKNOWN));
        headers().delegate().path(uri);
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

    @Override
    public HttpRequest setMethod(HttpMethod method) {
        this.method = ObjectUtil.checkNotNull(method, "method");
        headers().delegate().method(
                firstNonNull(com.linecorp.armeria.common.HttpMethod.tryParse(method.name()), UNKNOWN));
        return this;
    }

    @Override
    public HttpRequest setUri(String uri) {
        this.uri = ObjectUtil.checkNotNull(uri, "uri");
        headers().delegate().path(uri);
        return this;
    }

    @Override
    public HttpRequest setProtocolVersion(HttpVersion version) {
        super.setProtocolVersion(version);
        return this;
    }

    @Override
    public NettyHttp1Headers headers() {
        return (NettyHttp1Headers) super.headers();
    }

    @Override
    public int hashCode() {
        return Objects.hash(headers().delegate().hashCode(), super.hashCode());
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof NettyHttp1Request)) {
            return false;
        }

        final NettyHttp1Request other = (NettyHttp1Request) o;

        return headers().delegate().equals(other.headers().delegate()) &&
               super.equals(o);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("decoderResult", decoderResult())
                          .add("version", protocolVersion())
                          .add("method", method())
                          .add("headers", headers())
                          .toString();
    }
}
