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

import com.linecorp.armeria.common.stream.FixedStreamMessage.EmptyFixedStreamMessage;
import com.linecorp.armeria.common.stream.FixedStreamMessage.OneElementFixedStreamMessage;
import com.linecorp.armeria.common.stream.FixedStreamMessage.RegularFixedStreamMessage;
import com.linecorp.armeria.common.stream.FixedStreamMessage.TwoElementFixedStreamMessage;

/**
 * An {@link HttpRequest} optimized for when all the {@link HttpObject}s that will be published are known at
 * construction time.
 */
final class FixedHttpRequest {

    static final class EmptyFixedHttpRequest
            extends EmptyFixedStreamMessage<HttpObject> implements HttpRequest {

        private final HttpHeaders headers;
        private final boolean keepAlive;

        EmptyFixedHttpRequest(HttpHeaders headers, boolean keepAlive) {
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

    static final class OneElementFixedHttpRequest
            extends OneElementFixedStreamMessage<HttpObject> implements HttpRequest {

        private final HttpHeaders headers;
        private final boolean keepAlive;

        OneElementFixedHttpRequest(HttpHeaders headers, boolean keepAlive, HttpObject obj) {
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

    static final class TwoElementFixedHttpRequest
            extends TwoElementFixedStreamMessage<HttpObject> implements HttpRequest {

        private final HttpHeaders headers;
        private final boolean keepAlive;

        TwoElementFixedHttpRequest(
                HttpHeaders headers, boolean keepAlive, HttpObject obj1, HttpObject obj2) {
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

    static final class RegularFixedHttpRequest
            extends RegularFixedStreamMessage<HttpObject> implements HttpRequest {

        private final HttpHeaders headers;
        private final boolean keepAlive;

        RegularFixedHttpRequest(HttpHeaders headers, boolean keepAlive, HttpObject... objs) {
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
