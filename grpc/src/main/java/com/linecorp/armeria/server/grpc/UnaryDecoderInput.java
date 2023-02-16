/*
 *  Copyright 2022 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.server.grpc;

import com.linecorp.armeria.common.stream.StreamDecoderInput;

import io.netty.buffer.ByteBuf;

final class UnaryDecoderInput implements StreamDecoderInput {

    private final ByteBuf buf;

    UnaryDecoderInput(ByteBuf buf) {
        this.buf = buf;
    }

    @Override
    public int readableBytes() {
        return buf.readableBytes();
    }

    @Override
    public byte readByte() {
        return buf.readByte();
    }

    @Override
    public int readInt() {
        return buf.readInt();
    }

    @Override
    public long readLong() {
        return buf.readLong();
    }

    @Override
    public ByteBuf readBytes(int length) {
        if (length == readableBytes()) {
            return buf.retainedDuplicate();
        } else {
            return buf.readBytes(length);
        }
    }

    @Override
    public void readBytes(byte[] dst) {
        buf.readBytes(dst);
    }

    @Override
    public byte getByte(int index) {
        return buf.getByte(index);
    }

    @Override
    public void skipBytes(int length) {
        buf.skipBytes(length);
    }

    @Override
    public void close() {
        buf.release();
    }
}
