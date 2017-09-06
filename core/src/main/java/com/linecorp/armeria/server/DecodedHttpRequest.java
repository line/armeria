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

package com.linecorp.armeria.server;

import org.reactivestreams.Subscriber;

import com.linecorp.armeria.common.DefaultHttpRequest;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.internal.InboundTrafficController;

import io.netty.channel.EventLoop;

final class DecodedHttpRequest extends DefaultHttpRequest {

    private final EventLoop eventLoop;
    private final int id;
    private final int streamId;
    private final InboundTrafficController inboundTrafficController;
    private final long defaultMaxRequestLength;
    private ServiceRequestContext ctx;
    private long transferredBytes;

    DecodedHttpRequest(EventLoop eventLoop, int id, int streamId, HttpHeaders headers, boolean keepAlive,
                       InboundTrafficController inboundTrafficController, long defaultMaxRequestLength) {

        super(headers, keepAlive);

        this.eventLoop = eventLoop;
        this.id = id;
        this.streamId = streamId;
        this.inboundTrafficController = inboundTrafficController;
        this.defaultMaxRequestLength = defaultMaxRequestLength;
    }

    void init(ServiceRequestContext ctx) {
        this.ctx = ctx;
        ctx.logBuilder().requestHeaders(headers());
    }

    int id() {
        return id;
    }

    int streamId() {
        return streamId;
    }

    long maxRequestLength() {
        return ctx != null ? ctx.maxRequestLength() : defaultMaxRequestLength;
    }

    long transferredBytes() {
        return transferredBytes;
    }

    void increaseTransferredBytes(long delta) {
        if (transferredBytes > Long.MAX_VALUE - delta) {
            transferredBytes = Long.MAX_VALUE;
        } else {
            transferredBytes += delta;
        }
    }

    @Override
    public void subscribe(Subscriber<? super HttpObject> subscriber) {
        subscribe(subscriber, eventLoop);
    }

    @Override
    public boolean write(HttpObject obj) {
        final boolean published = super.write(obj);
        if (published && obj instanceof HttpData) {
            final int length = ((HttpData) obj).length();
            inboundTrafficController.inc(length);
            assert ctx != null : "uninitialized DecodedHttpRequest must be aborted.";
            ctx.logBuilder().requestLength(transferredBytes);
        }
        return published;
    }

    @Override
    protected void onRemoval(HttpObject obj) {
        if (obj instanceof HttpData) {
            final int length = ((HttpData) obj).length();
            inboundTrafficController.dec(length);
        }
    }
}
