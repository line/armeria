/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.internal.common;

import java.net.InetSocketAddress;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.ConnectionEventKey;
import com.linecorp.armeria.common.ConnectionEventListener;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.internal.common.util.ChannelUtil;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;

public class NoopKeepAliveHandler implements KeepAliveHandler {
    private static final Logger logger = LoggerFactory.getLogger(NoopKeepAliveHandler.class);
    private boolean closed;
    private boolean disconnectWhenFinished;
    private boolean isInitialized;
    private final Channel channel;
    private final ConnectionEventListener connectionEventListener;
    private final SessionProtocol protocol;

    public NoopKeepAliveHandler(Channel channel,
                                ConnectionEventListener connectionEventListener,
                                SessionProtocol protocol) {
        this.channel = channel;
        this.connectionEventListener = connectionEventListener;
        this.protocol = protocol;
    }

    @Override
    public void initialize(ChannelHandlerContext ctx) {
        if (isInitialized) {
            return;
        }

        isInitialized = true;

        if (!(ctx.handler() instanceof HttpProtocolUpgradeHandler)) {
            final ConnectionEventKey connectionEventKey = connectionEventKey(channel);
            connectionEventKey.setProtocol(protocol);

            try {
                connectionEventListener.connectionOpened(connectionEventKey.desiredProtocol(),
                                                         protocol,
                                                         connectionEventKey.remoteAddress(),
                                                         connectionEventKey.localAddress(),
                                                         channel);
                connectionEventListener.connectionActive(protocol,
                                                         connectionEventKey.remoteAddress(),
                                                         connectionEventKey.localAddress(),
                                                         channel);
            } catch (Throwable e) {
                if (logger.isWarnEnabled()) {
                    logger.warn("{} Exception handling {}.connectionOpened()",
                                channel, connectionEventListener.getClass().getName(), e);
                }
            }

            ctx.channel().closeFuture().addListener(unused -> {
                try {
                    connectionEventListener.connectionClosed(protocol,
                                                             connectionEventKey.remoteAddress(),
                                                             connectionEventKey.localAddress(),
                                                             false,
                                                             channel);
                    ChannelUtil.setConnectionEventKey(channel, null);
                } catch (Throwable e) {
                    if (logger.isWarnEnabled()) {
                        logger.warn("{} Exception handling {}.connectionClosed()",
                                    channel, connectionEventListener.getClass().getName(), e);
                    }
                }

                destroy();
            });
        }
    }

    @Override
    public void destroy() {
        closed = true;
    }

    @Override
    public boolean isHttp2() {
        return false;
    }

    @Override
    public void onReadOrWrite() {}

    @Override
    public void onPing() {}

    @Override
    public void onPingAck(long data) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isClosing() {
        return closed;
    }

    @Override
    public void disconnectWhenFinished() {
        disconnectWhenFinished = true;
    }

    @Override
    public boolean needsDisconnection() {
        return disconnectWhenFinished || closed;
    }

    @Override
    public void increaseNumRequests() {}

    private ConnectionEventKey connectionEventKey(Channel channel) {
        ConnectionEventKey connectionEventKey = ChannelUtil.connectionEventKey(channel);

        if (connectionEventKey != null) {
            return connectionEventKey;
        }

        final InetSocketAddress remoteAddress = ChannelUtil.remoteAddress(channel);
        final InetSocketAddress localAddress = ChannelUtil.localAddress(channel);

        assert localAddress != null && remoteAddress != null;

        connectionEventKey = new ConnectionEventKey(remoteAddress, localAddress);

        ChannelUtil.setConnectionEventKey(channel, connectionEventKey);

        return connectionEventKey;
    }
}
