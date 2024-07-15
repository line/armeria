/*
 * Copyright 2024 LINE Corporation
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

import static com.google.common.base.Preconditions.checkState;

import java.net.InetSocketAddress;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.ClientConnectionEventListener;
import com.linecorp.armeria.common.ConnectionEventListener;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.client.HttpSession;
import com.linecorp.armeria.internal.common.util.ChannelUtil;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import io.netty.util.AttributeMap;

public final class DelegatingConnectionEventListener {

    private static final AttributeKey<DelegatingConnectionEventListener> CONNECTION_EVENT_LISTENER_KEY =
            AttributeKey.valueOf(DelegatingConnectionEventListener.class, "CONNECTION_EVENT_LISTENER_KEY");

    @Nullable
    public static DelegatingConnectionEventListener getOrNull(Channel channel) {
        return channel.attr(CONNECTION_EVENT_LISTENER_KEY).get();
    }

    public static DelegatingConnectionEventListener get(Channel channel) {
        final DelegatingConnectionEventListener listener = getOrNull(channel);
        if (listener != null) {
            return listener;
        }
        throw new IllegalStateException("DelegatingConnectionEventListener not found");
    }

    private static final Logger logger = LoggerFactory.getLogger(DelegatingConnectionEventListener.class);

    private final ConnectionEventListener delegate;
    private final Channel channel;
    private final InetSocketAddress remoteAddress;
    private final SessionProtocol desiredProtocol;

    @Nullable
    private InetSocketAddress localAddress;
    @Nullable
    private SessionProtocol actualProtocol;

    public DelegatingConnectionEventListener(ConnectionEventListener delegate, Channel channel,
                                             SessionProtocol desiredProtocol, InetSocketAddress remoteAddress) {
        this.delegate = delegate;
        this.channel = channel;
        this.remoteAddress = remoteAddress;
        this.desiredProtocol = desiredProtocol;
        channel.attr(CONNECTION_EVENT_LISTENER_KEY).set(this);
    }

    private InetSocketAddress remoteAddress() {
        return remoteAddress;
    }

    /**
     * Lazily acquires and returns the local address of the {@link #channel} since it is valid only after the
     * connection is established.
     */
    private InetSocketAddress localAddress() {
        if (localAddress == null) {
            localAddress = ChannelUtil.localAddress(channel);
        }
        checkState(localAddress != null, "localAddress not available");
        return localAddress;
    }

    private SessionProtocol desiredProtocol() {
        return desiredProtocol;
    }

    private AttributeMap attrs() {
        return channel;
    }

    /**
     * Lazily acquires and returns the actual protocol of the {@link #channel} since it is valid only after the
     * connection is established.
     */
    private SessionProtocol actualProtocol() {
        if (actualProtocol == null) {
            actualProtocol = HttpSession.get(channel).protocol();
        }
        checkState(actualProtocol != null, "actualProtocol not available");
        return actualProtocol;
    }

    public void connectionPending() {
        if (!(delegate instanceof ClientConnectionEventListener)) {
            throw new UnsupportedOperationException(
                    "ClientConnectionEventListener is required for connectionPending()");
        }
        final ClientConnectionEventListener delegate = (ClientConnectionEventListener) this.delegate;
        final InetSocketAddress localAddress = localAddress();
        try {
            delegate.connectionPending(desiredProtocol(), remoteAddress(), localAddress, attrs());
        } catch (Throwable e) {
            logger.warn("{} Unexpected exception while invoking {}.connectionPending()",
                        channel, delegate.getClass().getName(), e);
        }
    }

    public void connectionFailed(Throwable cause, boolean wasPending) {
        if (!(delegate instanceof ClientConnectionEventListener)) {
            throw new UnsupportedOperationException(
                    "ClientConnectionEventListener is required for connectionFailed()");
        }
        final ClientConnectionEventListener delegate = (ClientConnectionEventListener) this.delegate;
        try {
            if (wasPending) {
                delegate.connectionFailed(desiredProtocol(), remoteAddress(), localAddress(), attrs(), cause,
                                          true);
            } else {
                delegate.connectionFailed(desiredProtocol(), remoteAddress(), null, attrs(), cause,
                                          true);
            }
        } catch (Throwable e) {
            logger.warn("{} Unexpected exception while invoking {}.connectionFailed()",
                        channel, delegate.getClass().getName(), e);
        }
    }

    public void connectionOpened() {
        final SessionProtocol actualProtocol = actualProtocol();
        try {
            delegate.connectionOpened(desiredProtocol(), actualProtocol, remoteAddress(), localAddress(),
                                      attrs());
        } catch (Throwable e) {
            logger.warn("{} Unexpected exception while invoking {}.connectionOpened()",
                        channel, delegate.getClass().getName(), e);
        }
    }

    public void connectionActive(boolean wasIdle) {
        try {
            delegate.connectionActive(actualProtocol(), remoteAddress(), localAddress(), attrs(), wasIdle);
        } catch (Throwable e) {
            logger.warn("{} Unexpected exception while invoking {}.connectionClosed()",
                        channel, delegate.getClass().getName(), e);
        }
    }

    public void connectionIdle() {
        try {
            delegate.connectionIdle(actualProtocol(), remoteAddress(), localAddress(), attrs());
        } catch (Throwable e) {
            logger.warn("{} Unexpected exception while invoking {}.connectionClosed()",
                        channel, delegate.getClass().getName(), e);
        }
    }

    public void connectionClosed(boolean wasIdle) {
        try {
            delegate.connectionClosed(actualProtocol(), remoteAddress(), localAddress(), attrs(), wasIdle);
        } catch (Throwable e) {
            logger.warn("{} Unexpected exception while invoking {}.connectionClosed()",
                        channel, delegate.getClass().getName(), e);
        }
    }
}
