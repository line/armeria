/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.http;

import org.reactivestreams.Subscriber;

import com.linecorp.armeria.common.http.DefaultHttpRequest;
import com.linecorp.armeria.common.http.HttpData;
import com.linecorp.armeria.common.http.HttpHeaders;
import com.linecorp.armeria.common.http.HttpObject;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.internal.InboundTrafficController;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.channel.EventLoop;

final class DecodedHttpRequest extends DefaultHttpRequest {

    private final EventLoop eventLoop;
    private final int id;
    private final int streamId;
    private final InboundTrafficController inboundTrafficController;
    private ServiceRequestContext ctx;
    private long writtenBytes;

    DecodedHttpRequest(EventLoop eventLoop, int id, int streamId, HttpHeaders headers, boolean keepAlive,
                       InboundTrafficController inboundTrafficController) {

        super(headers, keepAlive);

        this.eventLoop = eventLoop;
        this.id = id;
        this.streamId = streamId;
        this.inboundTrafficController = inboundTrafficController;
    }

    void init(ServiceRequestContext ctx) {
        this.ctx = ctx;
        ctx.requestLogBuilder().attr(RequestLog.HTTP_HEADERS).set(headers());
    }

    int id() {
        return id;
    }

    int streamId() {
        return streamId;
    }

    long maxRequestLength() {
        final ServiceRequestContext ctx = context();
        return ctx.maxRequestLength();
    }

    long writtenBytes() {
        return writtenBytes;
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
            context().requestLogBuilder().contentLength(writtenBytes += length);
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

    private ServiceRequestContext context() {
        final ServiceRequestContext ctx = this.ctx;
        assert ctx != null : "invoked before init()";
        return ctx;
    }
}
