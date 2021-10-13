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
package com.linecorp.armeria.common.websocket;

import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.armeria.common.ByteBufAccessMode;

import io.netty.buffer.ByteBuf;

abstract class WebSocketFrameWrapper implements WebSocketFrame {

    private final WebSocketFrame delegate;

    WebSocketFrameWrapper(WebSocketFrame delegate) {
        this.delegate = delegate;
    }

    WebSocketFrame delegate() {
        return delegate;
    }

    @Override
    public WebSocketFrameType type() {
        return delegate.type();
    }

    @Override
    public boolean isFinalFragment() {
        return delegate.isFinalFragment();
    }

    @Override
    public byte[] array() {
        return delegate.array();
    }

    @Override
    public int dataLength() {
        return delegate.dataLength();
    }

    @Override
    public boolean isPooled() {
        return delegate.isPooled();
    }

    @Override
    public ByteBuf byteBuf(ByteBufAccessMode mode) {
        return delegate.byteBuf(mode);
    }

    @Override
    public void close() {
        delegate.close();
    }

    @Override
    public int hashCode() {
        return delegate.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof WebSocketFrameWrapper)) {
            return false;
        }

        if (obj == this) {
            return true;
        }

        return delegate.equals(obj);
    }

    @Override
    public String toString() {
        return toString(MoreObjects.toStringHelper(this)).toString();
    }

    ToStringHelper toString(ToStringHelper toStringHelper) {
        return toStringHelper.add("type", type())
                             .add("isFinalFragment", isFinalFragment())
                             .add("dataLength", dataLength())
                             .add("isPooled", isPooled())
                             .add("payloadPreview", delegate.toString());
    }
}
