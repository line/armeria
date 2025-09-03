/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.internal.client;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.DefaultHttpResponse;
import com.linecorp.armeria.internal.common.InboundTrafficController;

import io.netty.channel.EventLoop;
import io.netty.util.concurrent.EventExecutor;

public final class DecodedHttpResponse extends DefaultHttpResponse {

    private final EventLoop eventLoop;
    @Nullable
    private InboundTrafficController inboundTrafficController;
    private long writtenBytes;
    private int streamId = -1;

    public DecodedHttpResponse(EventLoop eventLoop) {
        this.eventLoop = eventLoop;
    }

    public void init(InboundTrafficController inboundTrafficController) {
        this.inboundTrafficController = inboundTrafficController;
    }

    public void setStreamId(int streamId) {
        this.streamId = streamId;
    }

    public long writtenBytes() {
        return writtenBytes;
    }

    @Override
    public EventExecutor defaultSubscriberExecutor() {
        return eventLoop;
    }

    @VisibleForTesting
    @Nullable
    public InboundTrafficController inboundTrafficController() {
        return inboundTrafficController;
    }

    @Override
    public boolean tryWrite(HttpObject obj) {
        final boolean published = super.tryWrite(obj);
        if (published && obj instanceof HttpData) {
            final int length = ((HttpData) obj).length();
            assert inboundTrafficController != null;
            if (streamId == 1) {
                // The stream ID 1 is used for an HTTP/1 upgrade request.
            } else {
                inboundTrafficController.inc(length);
            }
            writtenBytes += length;
        }
        return published;
    }

    @Override
    protected void onRemoval(HttpObject obj) {
        if (obj instanceof HttpData) {
            final int length = ((HttpData) obj).length();
            assert inboundTrafficController != null;
            assert streamId > -1 : "id must be set before consuming HttpData";
            if (streamId == 1) {
                // The stream ID 1 is used for an HTTP/1 upgrade request.
            } else {
                inboundTrafficController.dec(streamId, length);
            }
        }
    }
}
