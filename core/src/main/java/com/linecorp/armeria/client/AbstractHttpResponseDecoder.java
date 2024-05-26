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

import java.net.InetSocketAddress;
import java.util.Iterator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.ConnectionEventListener;
import com.linecorp.armeria.common.ContentTooLargeException;
import com.linecorp.armeria.common.ContentTooLargeExceptionBuilder;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.client.DecodedHttpResponse;
import com.linecorp.armeria.internal.client.HttpSession;
import com.linecorp.armeria.internal.common.InboundTrafficController;
import com.linecorp.armeria.internal.common.util.ChannelUtil;

import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import io.netty.util.collection.IntObjectHashMap;
import io.netty.util.collection.IntObjectMap;

abstract class AbstractHttpResponseDecoder implements HttpResponseDecoder {

    private static final Logger logger = LoggerFactory.getLogger(AbstractHttpResponseDecoder.class);

    private final IntObjectMap<HttpResponseWrapper> responses = new IntObjectHashMap<>();
    private final Channel channel;
    private final InboundTrafficController inboundTrafficController;
    private final ConnectionEventListener connectionEventListener;

    @Nullable
    private HttpSession httpSession;

    private int unfinishedResponses;
    private boolean closing;
    final boolean needsKeepAliveHandler;
    private boolean isActive = true;
    private boolean hasOpened;

    AbstractHttpResponseDecoder(Channel channel, InboundTrafficController inboundTrafficController,
                                ConnectionEventListener connectionEventListener,
                                boolean needsKeepAliveHandler) {
        this.channel = channel;
        this.inboundTrafficController = inboundTrafficController;
        this.connectionEventListener = connectionEventListener;
        this.needsKeepAliveHandler = needsKeepAliveHandler;

        channel.closeFuture().addListener(future -> {
            if (!hasOpened) {
                return;
            }

            try {
                final InetSocketAddress remoteAddress = ChannelUtil.remoteAddress(channel);
                final InetSocketAddress localAddress = ChannelUtil.localAddress(channel);
                final SessionProtocol protocol = HttpSession.get(channel).protocol();

                assert remoteAddress != null && localAddress != null && protocol != null;

                connectionEventListener.connectionClosed(protocol, remoteAddress, localAddress, isActive,
                                                         channel);
            } catch (Throwable e) {
                if (logger.isWarnEnabled()) {
                    logger.warn("{} Exception handling {}.connectionClosed()",
                                channel, connectionEventListener.getClass().getName(), e);
                }
            }
        });
    }

    @Override
    public Channel channel() {
        return channel;
    }

    @Override
    public InboundTrafficController inboundTrafficController() {
        return inboundTrafficController;
    }

    @Override
    public HttpResponseWrapper addResponse(
            int id, DecodedHttpResponse res, ClientRequestContext ctx, EventLoop eventLoop) {
        final HttpResponseWrapper newRes =
                new HttpResponseWrapper(res, eventLoop, ctx,
                                        ctx.responseTimeoutMillis(), ctx.maxResponseLength());
        final HttpResponseWrapper oldRes = responses.put(id, newRes);
        keepAliveHandler().increaseNumRequests();

        assert oldRes == null : "addResponse(" + id + ", " + res + ", " + ctx + "): " + oldRes;
        onResponseAdded(id, eventLoop, newRes);
        return newRes;
    }

    abstract void onResponseAdded(int id, EventLoop eventLoop, HttpResponseWrapper responseWrapper);

    @Nullable
    @Override
    public HttpResponseWrapper getResponse(int id) {
        return responses.get(id);
    }

    @Nullable
    @Override
    public HttpResponseWrapper removeResponse(int id) {
        if (closing) {
            // `unfinishedResponses` will be removed by `failUnfinishedResponses()`
            return null;
        }

        final HttpResponseWrapper removed = responses.remove(id);
        if (removed != null) {
            unfinishedResponses--;
            assert unfinishedResponses >= 0 : unfinishedResponses;

            if (needsKeepAliveHandler && unfinishedResponses == 0) {
                isActive = false;

                final InetSocketAddress remoteAddress = ChannelUtil.remoteAddress(channel);
                final InetSocketAddress localAddress = ChannelUtil.localAddress(channel);
                final SessionProtocol protocol = session().protocol();

                assert remoteAddress != null && localAddress != null && protocol != null;

                try {
                    connectionEventListener.connectionIdle(protocol, localAddress, remoteAddress, channel);
                } catch (Throwable e) {
                    if (logger.isWarnEnabled()) {
                        logger.warn("{} Exception handling {}.connectionIdle()",
                                    channel, connectionEventListener.getClass().getName(), e);
                    }
                }
            }
        }
        return removed;
    }

    @Override
    public boolean hasUnfinishedResponses() {
        return unfinishedResponses != 0;
    }

    @Override
    public boolean reserveUnfinishedResponse(int maxUnfinishedResponses) {
        if (unfinishedResponses >= maxUnfinishedResponses) {
            return false;
        }

        hasOpened = true;

        unfinishedResponses++;

        if (unfinishedResponses == 1) {
            isActive = true;

            final InetSocketAddress remoteAddress = ChannelUtil.remoteAddress(channel);
            final InetSocketAddress localAddress = ChannelUtil.localAddress(channel);
            final SessionProtocol protocol = session().protocol();

            assert remoteAddress != null && localAddress != null && protocol != null;

            try {
                connectionEventListener.connectionActive(protocol, localAddress, remoteAddress, channel);
            } catch (Throwable e) {
                if (logger.isWarnEnabled()) {
                    logger.warn("{} Exception handling {}.connectionActive()",
                                channel, connectionEventListener.getClass().getName(), e);
                }
            }
        }

        return true;
    }

    @Override
    public void decrementUnfinishedResponses() {
        unfinishedResponses--;
    }

    @Override
    public void failUnfinishedResponses(Throwable cause) {
        if (closing) {
            return;
        }
        closing = true;

        for (final Iterator<HttpResponseWrapper> iterator = responses.values().iterator();
             iterator.hasNext();) {
            final HttpResponseWrapper res = iterator.next();
            // To avoid calling removeResponse by res.close(cause), remove before closing.
            iterator.remove();
            unfinishedResponses--;
            res.close(cause);
        }
    }

    @Override
    public HttpSession session() {
        if (httpSession != null) {
            return httpSession;
        }
        return httpSession = HttpSession.get(channel);
    }

    static ContentTooLargeException contentTooLargeException(HttpResponseWrapper res, long transferred) {
        final ContentTooLargeExceptionBuilder builder =
                ContentTooLargeException.builder()
                                        .maxContentLength(res.maxContentLength())
                                        .transferred(transferred);
        if (res.contentLengthHeaderValue() >= 0) {
            builder.contentLength(res.contentLengthHeaderValue());
        }
        return builder.build();
    }
}
