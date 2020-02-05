/*
 * Copyright 2019 LINE Corporation
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

import com.linecorp.armeria.internal.common.AbstractHttp2ConnectionHandlerBuilder;

import io.netty.channel.Channel;
import io.netty.handler.codec.http2.Http2ConnectionDecoder;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2Settings;

final class Http2ClientConnectionHandlerBuilder
        extends AbstractHttp2ConnectionHandlerBuilder<Http2ClientConnectionHandler,
                                                      Http2ClientConnectionHandlerBuilder> {

    private final HttpClientFactory clientFactory;

    Http2ClientConnectionHandlerBuilder(Channel ch, HttpClientFactory clientFactory) {
        super(ch);
        this.clientFactory = clientFactory;
    }

    @Override
    protected Http2ClientConnectionHandler build(Http2ConnectionDecoder decoder,
                                                 Http2ConnectionEncoder encoder,
                                                 Http2Settings initialSettings) throws Exception {
        return new Http2ClientConnectionHandler(decoder, encoder, initialSettings, channel(), clientFactory);
    }
}
