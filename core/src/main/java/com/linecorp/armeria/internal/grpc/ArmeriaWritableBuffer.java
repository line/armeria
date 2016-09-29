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
package com.linecorp.armeria.internal.grpc;

import io.grpc.internal.WritableBuffer;

/**
 * The {@link WritableBuffer} used by Armeria.
 */
public final class ArmeriaWritableBuffer implements WritableBuffer {

    private final byte[] buf;
    private int index;

    ArmeriaWritableBuffer(int capacityHint) {
        buf = new byte[capacityHint];
    }

    public byte[] array() {
        return buf;
    }

    @Override
    public int writableBytes() {
        return buf.length - index;
    }

    @Override
    public int readableBytes() {
        return index;
    }

    @Override
    public void write(byte[] src, int srcIndex, int length) {
        System.arraycopy(src, srcIndex, buf, index, length);
        index += length;
    }

    @Override
    public void write(byte b) {
        buf[index++] = b;
    }

    @Override
    public void release() {}
}
