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

import com.linecorp.armeria.common.stream.EmptyFixedStreamMessage;
import com.linecorp.armeria.common.stream.OneElementFixedStreamMessage;
import com.linecorp.armeria.common.stream.RegularFixedStreamMessage;
import com.linecorp.armeria.common.stream.TwoElementFixedStreamMessage;

/**
 * An {@link HttpRequest} optimized for when all the {@link HttpObject}s that will be published are known at
 * construction time.
 */
final class FixedHttpRequest {

    // TODO(minwoox): Override toDuplicator(...) methods for optimization

    static final class EmptyFixedHttpRequest
            extends EmptyFixedStreamMessage<HttpObject> implements HttpRequest {

        private final RequestHeaders headers;
        private final RequestOptions options;

        EmptyFixedHttpRequest(RequestHeaders headers, RequestOptions options) {
            this.headers = headers;
            this.options = options;
        }

        EmptyFixedHttpRequest(RequestHeaders headers) {
            this(headers, RequestOptions.of());
        }

        @Override
        public RequestHeaders headers() {
            return headers;
        }

        @Override
        public RequestOptions options() {
            return options;
        }
    }

    static final class OneElementFixedHttpRequest
            extends OneElementFixedStreamMessage<HttpObject> implements HttpRequest {

        private final RequestHeaders headers;
        private final RequestOptions options;

        OneElementFixedHttpRequest(RequestHeaders headers, RequestOptions options, HttpObject obj) {
            super(obj);
            this.headers = headers;
            this.options = options;
        }

        OneElementFixedHttpRequest(RequestHeaders headers, HttpObject obj) {
            this(headers, RequestOptions.of(), obj);
        }

        @Override
        public RequestHeaders headers() {
            return headers;
        }

        @Override
        public RequestOptions options() {
            return options;
        }
    }

    static final class TwoElementFixedHttpRequest
            extends TwoElementFixedStreamMessage<HttpObject> implements HttpRequest {

        private final RequestHeaders headers;
        private final RequestOptions options;

        TwoElementFixedHttpRequest(
                RequestHeaders headers, RequestOptions options, HttpObject obj1, HttpObject obj2) {
            super(obj1, obj2);
            this.headers = headers;
            this.options = options;
        }

        TwoElementFixedHttpRequest(RequestHeaders headers, HttpObject obj1, HttpObject obj2) {
            this(headers, RequestOptions.of(), obj1, obj2);
        }

        @Override
        public RequestHeaders headers() {
            return headers;
        }

        @Override
        public RequestOptions options() {
            return options;
        }
    }

    static final class RegularFixedHttpRequest
            extends RegularFixedStreamMessage<HttpObject> implements HttpRequest {

        private final RequestHeaders headers;
        private final RequestOptions options;

        RegularFixedHttpRequest(RequestHeaders headers, RequestOptions options, HttpObject... objs) {
            super(objs);
            this.headers = headers;
            this.options = options;
        }

        RegularFixedHttpRequest(RequestHeaders headers, HttpObject... objs) {
            this(headers, RequestOptions.of(), objs);
        }

        @Override
        public RequestHeaders headers() {
            return headers;
        }

        @Override
        public RequestOptions options() {
            return options;
        }
    }

    private FixedHttpRequest() {}
}
