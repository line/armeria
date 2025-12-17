/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

import com.linecorp.armeria.client.ConnectionPoolListener;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.util.ChannelUtil;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

/**
 * A non-threadsafe implementation of {@link ConnectionEventListener}.
 */
public final class DefaultConnectionEventListener implements ConnectionEventListener {

    private static final Logger logger = LoggerFactory.getLogger(DefaultConnectionEventListener.class);
    public static final AttributeKey<ConnectionEventListener> LISTENER =
            AttributeKey.valueOf(DefaultConnectionEventListener.class, "LISTENER");

    private final SessionProtocol sessionProtocol;
    private final ConnectionPoolListener connectionPoolListener;
    private final Channel channel;
    private CloseHint closeHint = CloseHint.UNKNOWN;

    private boolean closed;
    @Nullable
    private InetSocketAddress remoteAddress;
    @Nullable
    private InetSocketAddress localAddress;

    DefaultConnectionEventListener(SessionProtocol sessionProtocol,
                                   ConnectionPoolListener connectionPoolListener, Channel channel) {
        this.sessionProtocol = sessionProtocol;
        this.connectionPoolListener = connectionPoolListener;
        this.channel = channel;
    }

    private InetSocketAddress localAddress() {
        if (localAddress == null) {
            localAddress = ChannelUtil.localAddress(channel);
            assert localAddress != null;
        }
        return localAddress;
    }

    private InetSocketAddress remoteAddress() {
        if (remoteAddress == null) {
            remoteAddress = ChannelUtil.remoteAddress(channel);
            assert remoteAddress != null;
        }
        return remoteAddress;
    }

    @Override
    public void connectionOpened() {
        safelyNotify(() -> connectionPoolListener.connectionOpen(sessionProtocol, remoteAddress(),
                                                                 localAddress(), channel),
                     "connectionOpened");
    }

    @Override
    public void connectionClosed() {
        closed = true;
        safelyNotify(() -> connectionPoolListener.connectionClosed(sessionProtocol, remoteAddress(),
                                                                   localAddress(), channel, closeHint.name()),
                     "connectionClosed");
    }

    @Override
    public void pingWrite(long id) {
        if (closed) {
            return;
        }
        safelyNotify(() -> connectionPoolListener.pingWrite(sessionProtocol, remoteAddress(),
                                                            localAddress(), channel, id),
                     "pingWrite");
    }

    @Override
    public void pingAck(long id) {
        if (closed) {
            return;
        }
        safelyNotify(() -> connectionPoolListener.pingAck(sessionProtocol, remoteAddress(),
                                                          localAddress(), channel, id),
                     "pingAck");
    }

    void safelyNotify(ThrowingRunnable runnable, String name) {
        try {
            runnable.run();
        } catch (Throwable e) {
            if (logger.isWarnEnabled()) {
                logger.warn("{} Exception handling {}.{}()",
                            channel, connectionPoolListener.getClass().getName(), name, e);
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Throwable;
    }

    @Override
    public void closeHint(CloseHint closeHint) {
        this.closeHint = closeHint;
    }
}
