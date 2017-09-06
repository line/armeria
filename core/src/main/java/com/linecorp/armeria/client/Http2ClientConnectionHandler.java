/*
 * Copyright 2016 LINE Corporation
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

import com.linecorp.armeria.internal.AbstractHttp2ConnectionHandler;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.Http2ConnectionDecoder;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2Settings;

final class Http2ClientConnectionHandler extends AbstractHttp2ConnectionHandler {

    private final Http2ResponseDecoder responseDecoder;

    Http2ClientConnectionHandler(
            Http2ConnectionDecoder decoder, Http2ConnectionEncoder encoder,
            Http2Settings initialSettings, Http2ResponseDecoder responseDecoder) {

        super(decoder, encoder, initialSettings);
        this.responseDecoder = responseDecoder;
        connection().addListener(responseDecoder);
        decoder().frameListener(responseDecoder);
    }

    Http2ResponseDecoder responseDecoder() {
        return responseDecoder;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);

        // NB: Http2ConnectionHandler does not flush the preface string automatically.
        ctx.flush();
    }

    @Override
    protected void onCloseRequest(ChannelHandlerContext ctx) throws Exception {
        HttpSession.get(ctx.channel()).deactivate();
    }
}
