/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.common;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.stream.FixedStreamMessage.EmptyFixedStreamMessage;
import com.linecorp.armeria.common.stream.FixedStreamMessage.OneElementFixedStreamMessage;
import com.linecorp.armeria.common.stream.FixedStreamMessage.RegularFixedStreamMessage;
import com.linecorp.armeria.common.stream.FixedStreamMessage.TwoElementFixedStreamMessage;

/**
 * An {@link HttpRequest} optimized for when all the {@link HttpObject}s that will be published are known at
 * construction time.
 */
public final class FixedHttpRequest {

    /**
     * Creates a new {@link HttpRequest} that will publish the given {@link HttpHeaders} with keep-alive
     * enabled.
     */
    public static HttpRequest of(HttpHeaders headers) {
        return of(true, headers);
    }

    /**
     * Creates a new {@link HttpRequest} that will publish the given {@link HttpHeaders} with the
     * provided {@code keepAlive}.
     */
    public static HttpRequest of(boolean keepAlive, HttpHeaders headers) {
        requireNonNull(headers, "headers");
        return new OneElementFixedHttpRequest(headers, headers, keepAlive);
    }

    /**
     * Creates a new {@link FixedHttpRequest} that will publish the given {@link HttpHeaders} and
     * {@link HttpObject} with keep-alive enabled.
     */
    public static HttpRequest of(HttpHeaders headers, HttpObject content) {
        return of(true, headers, content);
    }

    /**
     * Creates a new {@link HttpRequest} that will publish the given {@link HttpHeaders} and
     * {@link HttpObject} with the provided {@code keepAlive}.
     */
    public static HttpRequest of(boolean keepAlive, HttpHeaders headers, HttpObject content) {
        requireNonNull(headers, "headers");
        requireNonNull(content, "content");
        return new TwoElementFixedHttpRequest(headers, headers, content, keepAlive);
    }

    /**
     * Creates a new {@link HttpRequest} that will publish the given {@code objs}. {@code objs} is not
     * copied so must not be mutated after this method call (it is generally meant to be used with a varargs
     * invocation). The first element of {@code objs} must be {@link HttpHeaders}. Keep-alive is enabled.
     */
    public static HttpRequest of(HttpObject... objs) {
        return of(true, objs);
    }

    /**
     * Creates a new {@link HttpRequest} that will publish the given {@code objs}. {@code objs} is not
     * copied so must not be mutated after this method call (it is generally meant to be used with a varargs
     * invocation). The first element of {@code objs} must be {@link HttpHeaders}.
     */
    public static HttpRequest of(boolean keepalive, HttpObject... objs) {
        requireNonNull(objs, "objs");
        checkArgument(objs.length > 0, "objs must be non-empty");
        checkArgument(objs[0] instanceof HttpHeaders);
        HttpHeaders headers = (HttpHeaders) objs[0];
        switch (objs.length) {
            case 1:
                return of(keepalive, headers);
            case 2:
                return of(keepalive, headers, objs[0]);
            default:
                return new RegularFixedHttpRequest(headers, objs, keepalive);
        }
    }

    /**
     * Creates a new {@link HttpRequest} that will publish the given {@code objs}. {@code objs} is not
     * copied so must not be mutated after this method call (it is generally meant to be used with a varargs
     * invocation). It is assumed the provided {@link HttpHeaders} were already written outside this stream.
     */
    static HttpRequest ofWrittenHeaders(HttpHeaders headers, HttpObject... objs) {
        requireNonNull(objs, "objs");
        switch (objs.length) {
            case 0:
                return new EmptyFixedHttpRequest(headers, true);
            case 1:
                return new OneElementFixedHttpRequest(headers, objs[0], true);
            case 2:
                return new TwoElementFixedHttpRequest(headers, objs[0], objs[1], true);
            default:
                return new RegularFixedHttpRequest(headers, objs, true);
        }
    }

    private static final class EmptyFixedHttpRequest
            extends EmptyFixedStreamMessage<HttpObject> implements HttpRequest {

        private final HttpHeaders headers;
        private final boolean keepAlive;

        private EmptyFixedHttpRequest(HttpHeaders headers, boolean keepAlive) {
            this.headers = headers;
            this.keepAlive = keepAlive;
        }

        @Override
        public HttpHeaders headers() {
            return headers;
        }

        @Override
        public boolean isKeepAlive() {
            return keepAlive;
        }
    }

    private static final class OneElementFixedHttpRequest
            extends OneElementFixedStreamMessage<HttpObject> implements HttpRequest {

        private final HttpHeaders headers;
        private final boolean keepAlive;

        private OneElementFixedHttpRequest(HttpHeaders headers, HttpObject obj, boolean keepAlive) {
            super(obj);
            this.headers = headers;
            this.keepAlive = keepAlive;
        }

        @Override
        public HttpHeaders headers() {
            return headers;
        }

        @Override
        public boolean isKeepAlive() {
            return keepAlive;
        }
    }

    private static final class TwoElementFixedHttpRequest
            extends TwoElementFixedStreamMessage<HttpObject> implements HttpRequest {

        private final HttpHeaders headers;
        private final boolean keepAlive;

        private TwoElementFixedHttpRequest(
                HttpHeaders headers, HttpObject obj1, HttpObject obj2, boolean keepAlive) {
            super(obj1, obj2);
            this.headers = headers;
            this.keepAlive = keepAlive;
        }

        @Override
        public HttpHeaders headers() {
            return headers;
        }

        @Override
        public boolean isKeepAlive() {
            return keepAlive;
        }
    }

    private static final class RegularFixedHttpRequest
            extends RegularFixedStreamMessage<HttpObject> implements HttpRequest {

        private final HttpHeaders headers;
        private final boolean keepAlive;

        private RegularFixedHttpRequest(HttpHeaders headers, HttpObject[] objs, boolean keepAlive) {
            super(objs);
            this.headers = headers;
            this.keepAlive = keepAlive;
        }

        @Override
        public HttpHeaders headers() {
            return headers;
        }

        @Override
        public boolean isKeepAlive() {
            return keepAlive;
        }
    }

    private FixedHttpRequest() {}
}
