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

import java.util.Iterator;

import com.linecorp.armeria.common.ContentTooLargeException;
import com.linecorp.armeria.common.ContentTooLargeExceptionBuilder;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.client.DecodedHttpResponse;
import com.linecorp.armeria.internal.client.HttpSession;
import com.linecorp.armeria.internal.common.InboundTrafficController;
import com.linecorp.armeria.internal.common.KeepAliveHandler;

import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import io.netty.util.collection.IntObjectHashMap;
import io.netty.util.collection.IntObjectMap;

abstract class HttpResponseDecoder {

    private final IntObjectMap<AbstractHttpResponseWrapper> responses = new IntObjectHashMap<>();
    private final Channel channel;
    private final InboundTrafficController inboundTrafficController;

    @Nullable
    private HttpSession httpSession;

    private int unfinishedResponses;
    private boolean closing;

    HttpResponseDecoder(Channel channel, InboundTrafficController inboundTrafficController) {
        this.channel = channel;
        this.inboundTrafficController = inboundTrafficController;
    }

    final Channel channel() {
        return channel;
    }

    final InboundTrafficController inboundTrafficController() {
        return inboundTrafficController;
    }

    AbstractHttpResponseWrapper addResponse(
            int id, DecodedHttpResponse res, ClientRequestContext ctx,
            EventLoop eventLoop, long responseTimeoutMillis, long maxContentLength, boolean http1WebSocket) {
        final AbstractHttpResponseWrapper newRes;
        if (http1WebSocket) {
            newRes = new WebSocketHttp1ResponseWrapper(res, eventLoop, ctx,
                                                       responseTimeoutMillis, maxContentLength);
        } else {
            newRes = new HttpResponseWrapper(res, eventLoop, ctx, responseTimeoutMillis, maxContentLength);
        }
        final AbstractHttpResponseWrapper oldRes = responses.put(id, newRes);
        final KeepAliveHandler keepAliveHandler = keepAliveHandler();
        if (keepAliveHandler != null) {
            keepAliveHandler.increaseNumRequests();
        }

        assert oldRes == null : "addResponse(" + id + ", " + res + ", " + responseTimeoutMillis + "): " +
                                oldRes;
        onResponseAdded(id, eventLoop, newRes);
        return newRes;
    }

    abstract void onResponseAdded(int id, EventLoop eventLoop, AbstractHttpResponseWrapper responseWrapper);

    @Nullable
    final AbstractHttpResponseWrapper getResponse(int id) {
        return responses.get(id);
    }

    @Nullable
    final AbstractHttpResponseWrapper removeResponse(int id) {
        if (closing) {
            // `unfinishedResponses` will be removed by `failUnfinishedResponses()`
            return null;
        }

        final AbstractHttpResponseWrapper removed = responses.remove(id);
        if (removed != null) {
            unfinishedResponses--;
            assert unfinishedResponses >= 0 : unfinishedResponses;
        }
        return removed;
    }

    final boolean hasUnfinishedResponses() {
        return unfinishedResponses != 0;
    }

    final boolean reserveUnfinishedResponse(int maxUnfinishedResponses) {
        if (unfinishedResponses >= maxUnfinishedResponses) {
            return false;
        }

        unfinishedResponses++;
        return true;
    }

    final void decrementUnfinishedResponses() {
        unfinishedResponses--;
    }

    final void failUnfinishedResponses(Throwable cause) {
        if (closing) {
            return;
        }
        closing = true;

        for (final Iterator<AbstractHttpResponseWrapper> iterator = responses.values().iterator();
             iterator.hasNext();) {
            final AbstractHttpResponseWrapper res = iterator.next();
            // To avoid calling removeResponse by res.close(cause), remove before closing.
            iterator.remove();
            unfinishedResponses--;
            res.close(cause);
        }
    }

    HttpSession session() {
        if (httpSession != null) {
            return httpSession;
        }
        return httpSession = HttpSession.get(channel);
    }

    @Nullable
    abstract KeepAliveHandler keepAliveHandler();

    final boolean needsToDisconnectNow() {
        return !session().isAcquirable() && !hasUnfinishedResponses();
    }

    static Exception contentTooLargeException(AbstractHttpResponseWrapper res, long transferred) {
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
