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
package com.linecorp.armeria.server;

import com.linecorp.armeria.internal.common.AbstractHttp2ConnectionHandlerBuilder;

import io.micrometer.core.instrument.Timer;
import io.netty.channel.Channel;
import io.netty.handler.codec.http2.Http2ConnectionDecoder;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2Settings;

final class Http2ServerConnectionHandlerBuilder
        extends AbstractHttp2ConnectionHandlerBuilder<Http2ServerConnectionHandler,
                                                      Http2ServerConnectionHandlerBuilder> {

    private final ServerConfig config;
    private final Timer keepAliveTimer;
    private final GracefulShutdownSupport gracefulShutdownSupport;
    private final String scheme;

    Http2ServerConnectionHandlerBuilder(Channel ch, ServerConfig config, Timer keepAliveTimer,
                                        GracefulShutdownSupport gracefulShutdownSupport, String scheme) {
        super(ch);
        this.config = config;
        this.keepAliveTimer = keepAliveTimer;
        this.gracefulShutdownSupport = gracefulShutdownSupport;
        this.scheme = scheme;
        final long timeout = config.idleTimeoutMillis();
        if (timeout > 0) {
            gracefulShutdownTimeoutMillis(timeout);
        } else {
            // Timeout disabled.
            gracefulShutdownTimeoutMillis(-1);
        }
    }

    @Override
    protected Http2ServerConnectionHandler build(Http2ConnectionDecoder decoder,
                                                 Http2ConnectionEncoder encoder,
                                                 Http2Settings initialSettings) throws Exception {
        return new Http2ServerConnectionHandler(decoder, encoder, initialSettings, channel(),
                                                config, keepAliveTimer, gracefulShutdownSupport, scheme);
    }
}
