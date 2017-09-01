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

import org.reactivestreams.Subscriber;

import com.linecorp.armeria.common.DefaultHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.internal.InboundTrafficController;

import io.netty.channel.EventLoop;

final class DecodedHttpResponse extends DefaultHttpResponse {

    private final EventLoop eventLoop;
    private InboundTrafficController inboundTrafficController;
    private long writtenBytes;

    DecodedHttpResponse(EventLoop eventLoop) {
        this.eventLoop = eventLoop;
    }

    void init(InboundTrafficController inboundTrafficController) {
        this.inboundTrafficController = inboundTrafficController;
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
            writtenBytes += length;
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
