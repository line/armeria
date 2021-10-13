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
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.SafeCloseable;

import io.netty.buffer.ByteBuf;

/**
 * An input of {@link HttpDecoder} which is used to read a stream of {@link HttpData}.
 */
@UnstableApi
public interface HttpDecoderInput extends SafeCloseable {

    /**
     * Returns the number of readable bytes.
     */
    int readableBytes();

    /**
     * Reads a byte from the readable bytes.
     */
    byte readByte();

    /**
     * Reads an unsigned byte from the readable bytes.
     */
    default short readUnsignedByte() {
        return (short) (readByte() & 0xFF);
    }

    /**
     * Reads an unsigned short from the readable bytes.
     *
     * @throws IllegalStateException if the {@link #readableBytes()} is less than {@code 2} bytes.
     */
    default int readUnsignedShort() {
        return (readByte() & 0xFF) << 8 | readByte();
    }

    /**
     * Reads a 32-bit integer from the readable bytes.
     *
     * @throws IllegalStateException if the {@link #readableBytes()} is less than {@code 4} bytes.
     */
    int readInt();

    /**
     * Reads a 64-bit long from the readable bytes.
     *
     * @throws IllegalStateException if the {@link #readableBytes()} is less than {@code 8} bytes.
     */
    long readLong();

    /**
     * Reads a newly retained slice of this {@link ByteBuf} from the readable bytes.
     *
     * @throws IllegalStateException if the specified {@code length} is greater than {@link #readableBytes()}
     */
    ByteBuf readBytes(int length);

    /**
     * Reads data to the specified {@code dst}.
     *
     * @throws IllegalStateException if the length of the {@code dst} is greater than {@link #readableBytes()}
     */
    void readBytes(byte[] dst);

    /**
     * Returns a byte at the specified absolute {@code index} in this {@link HttpDecoderInput}.
     *
     * @throws IllegalStateException if the specified {@code index} is greater than {@link #readableBytes()}
     */
    byte getByte(int index);

    /**
     * Skips bytes of the specified {@code length} in this {@link HttpDecoderInput}.
     *
     * @throws IllegalStateException if the specified {@code length} is greater than {@link #readableBytes()}
     */
    void skipBytes(int length);
}
