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

package com.linecorp.armeria.common.stream;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.util.SafeCloseable;

import io.netty.buffer.ByteBuf;

/**
 * An input of {@link HttpDeframer} which is used to read a stream of {@link HttpData}.
 */
public interface HttpDeframerInput extends SafeCloseable {

    /**
     * Returns the number of readable bytes.
     */
    int readableBytes();

    /**
     * Returns a byte at the current {@code readerIndex} and increases the {@code readerIndex} by {@code 1}.
     */
    byte readByte();

    /**
     * Returns a unsigned byte at the current {@code readerIndex} and increases the {@code readerIndex}
     * by {@code 1}.
     */
    default short readUnsignedByte() {
        return (short) (readByte() & 0xFF);
    }

    /**
     * Returns a 32-bit integer at the current {@code readerIndex} and increases the {@code readerIndex}
     * by {@code 4}.
     */
    int readInt();

    /**
     * Returns a newly retained slice of this {@link ByteBuf} at the current {@code readerIndex}
     * and increases the {@code readerIndex} by the specified {@code length}.
     *
     * @throws IllegalStateException if the specified {@code length} is greater than {@link #readableBytes()}
     */
    ByteBuf readBytes(int length);
}
