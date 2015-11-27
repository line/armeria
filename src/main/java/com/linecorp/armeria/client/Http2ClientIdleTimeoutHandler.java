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

package com.linecorp.armeria.client;

import java.util.concurrent.TimeUnit;

import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http2.HttpConversionUtil.ExtensionHeaderNames;

/**
 * A {@link HttpClientIdleTimeoutHandler} that ignores the responses received from the upgrade stream.
 */
class Http2ClientIdleTimeoutHandler extends HttpClientIdleTimeoutHandler {

    Http2ClientIdleTimeoutHandler(long idleTimeoutMillis) {
        super(idleTimeoutMillis);
    }

    Http2ClientIdleTimeoutHandler(long idleTimeout, TimeUnit timeUnit) {
        super(idleTimeout, timeUnit);
    }

    @Override
    boolean isResponseEnd(Object msg) {
        if (!(msg instanceof FullHttpResponse)) {
            return false;
        }

        return !"1".equals(((HttpMessage) msg).headers().get(ExtensionHeaderNames.STREAM_ID.text()));
    }
}
