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
package com.linecorp.armeria.client;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.internal.client.DecodedHttpResponse;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpHeaderValues;

final class WebSocketHttp2RequestSubscriber extends HttpRequestSubscriber {

    WebSocketHttp2RequestSubscriber(Channel ch, ClientHttpObjectEncoder encoder,
                                    HttpResponseDecoder responseDecoder,
                                    HttpRequest request, DecodedHttpResponse originalRes,
                                    ClientRequestContext ctx, long timeoutMillis) {
        super(ch, encoder, responseDecoder, request, originalRes, ctx, timeoutMillis);
    }

    @Override
    RequestHeaders mapHeaders(RequestHeaders headers) {
        if (headers.method() == HttpMethod.CONNECT) {
            return headers;
        }
        return headers.toBuilder()
                      .method(HttpMethod.CONNECT)
                      .removeAndThen(HttpHeaderNames.CONNECTION)
                      .removeAndThen(HttpHeaderNames.UPGRADE)
                      .removeAndThen(HttpHeaderNames.SEC_WEBSOCKET_KEY)
                      .set(HttpHeaderNames.PROTOCOL, HttpHeaderValues.WEBSOCKET.toString())
                      .build();
    }
}

