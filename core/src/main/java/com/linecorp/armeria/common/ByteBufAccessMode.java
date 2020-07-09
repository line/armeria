/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.armeria.common;

import com.linecorp.armeria.common.util.UnstableApi;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;

/**
 * Specifies the way a {@link ByteBuf} is retrieved from an {@link HttpData}.
 */
@UnstableApi
public enum ByteBufAccessMode {
    /**
     * Gets the duplicate (or slice) of the underlying {@link ByteBuf}. This mode is useful when you access
     * the {@link ByteBuf} within the life cycle of the {@link HttpData}:
     * <pre>{@code
     * try (HttpContent content = ...) {
     *     ByteBuf buf = content.byteBuf(ByteBufAccessMode.DUPLICATE);
     *     // Read something from 'buf' here.
     * }
     * }</pre>
     *
     * @see ByteBuf#duplicate()
     * @see ByteBuf#slice()
     */
    DUPLICATE,
    /**
     * Gets the retained duplicate (or slice) of the underlying {@link ByteBuf}. This mode is useful when
     * you access the {@link ByteBuf} beyond the life cycle of the {@link HttpData}, such as creating
     * another {@link HttpData} that shares the {@link ByteBuf}'s memory region:
     * <pre>{@code
     * HttpData data1 = HttpData.wrap(byteBuf);
     * HttpData data2 = HttpData.wrap(data1.byteBuf(ByteBufAccessMode.RETAINED_DUPLICATE));
     * }</pre>
     *
     * @see ByteBuf#retainedDuplicate()
     * @see ByteBuf#retainedSlice()
     */
    RETAINED_DUPLICATE,
    /**
     * Converts the underlying {@link ByteBuf} into a direct {@link ByteBuf} if necessary. If the underlying
     * {@link ByteBuf} is already direct, it behaves same with {@link #RETAINED_DUPLICATE}. Otherwise, a new
     * direct {@link ByteBuf} is allocated and the content of the underlying {@link ByteBuf} is copied into it.
     * This access mode is useful when you perform direct I/O or send data to a Netty {@link Channel}.
     */
    DIRECT
}
