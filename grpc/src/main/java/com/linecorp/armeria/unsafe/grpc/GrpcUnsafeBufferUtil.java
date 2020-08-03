/*
 * Copyright 2018 LINE Corporation
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

package com.linecorp.armeria.unsafe.grpc;

import java.util.IdentityHashMap;

import com.google.common.annotations.VisibleForTesting;
import com.google.protobuf.Message;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.netty.buffer.ByteBuf;
import io.netty.util.AttributeKey;

/**
 * Provides utility methods useful for storing and releasing the {@link ByteBuf} backing a {@link Message}.
 */
@UnstableApi
public final class GrpcUnsafeBufferUtil {

    /**
     * An {@link AttributeKey} for storing the {@link ByteBuf}s backing {@link Message}s.
     */
    @VisibleForTesting
    public static final AttributeKey<IdentityHashMap<Object, ByteBuf>> BUFFERS = AttributeKey.valueOf(
            GrpcUnsafeBufferUtil.class, "BUFFERS");

    /**
     * Stores the {@link ByteBuf} backing the specified {@link Message} to be released later using
     * {@link #releaseBuffer(Object, RequestContext)}.
     */
    public static void storeBuffer(ByteBuf buf, Object message, RequestContext ctx) {
        IdentityHashMap<Object, ByteBuf> buffers = ctx.attr(BUFFERS);
        if (buffers == null) {
            buffers = new IdentityHashMap<>();
            ctx.setAttr(BUFFERS, buffers);
        }
        buffers.put(message, buf);
    }

    /**
     * Releases the {@link ByteBuf} backing the specified {@link Message}.
     */
    public static void releaseBuffer(Object message, RequestContext ctx) {
        final IdentityHashMap<Object, ByteBuf> buffers = ctx.attr(BUFFERS);
        if (buffers == null) {
            return;
        }
        final ByteBuf removed = buffers.remove(message);
        if (removed == null) {
            return;
        }

        removed.release();
    }

    private GrpcUnsafeBufferUtil() {}
}
