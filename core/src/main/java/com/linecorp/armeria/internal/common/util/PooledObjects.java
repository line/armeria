/*
 * Copyright 2017 LINE Corporation
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
package com.linecorp.armeria.internal.common.util;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;

/**
 * Utility class that deals with pooled objects such as {@link ByteBufHolder} and {@link ByteBuf}.
 */
public final class PooledObjects {

    /**
     * Converts the given object to an unpooled copy and releases the given object.
     */
    public static <T> T toUnpooled(T o) {
        if (o instanceof ByteBufHolder) {
            o = copyAndRelease((ByteBufHolder) o);
        } else if (o instanceof ByteBuf) {
            o = copyAndRelease((ByteBuf) o);
        }
        return o;
    }

    private static <T> T copyAndRelease(ByteBufHolder o) {
        try {
            final ByteBuf content = Unpooled.wrappedBuffer(ByteBufUtil.getBytes(o.content()));
            @SuppressWarnings("unchecked")
            final T copy = (T) o.replace(content);
            return copy;
        } finally {
            ReferenceCountUtil.safeRelease(o);
        }
    }

    private static <T> T copyAndRelease(ByteBuf o) {
        try {
            @SuppressWarnings("unchecked")
            final T copy = (T) Unpooled.copiedBuffer(o);
            return copy;
        } finally {
            ReferenceCountUtil.safeRelease(o);
        }
    }

    private PooledObjects() {}
}
