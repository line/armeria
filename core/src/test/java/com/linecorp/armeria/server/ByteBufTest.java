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

package com.linecorp.armeria.server;

import java.nio.ByteBuffer;

import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;

class ByteBufTest {
    @Test
    void test() {
        final ByteBuf byteBuf = PooledByteBufAllocator.DEFAULT.directBuffer(10);
        byteBuf.writeInt(10);
        final ByteBuffer byteBuffer = byteBuf.nioBuffer();
        byteBuf.release();
        System.out.println(byteBuffer);
        final int anInt = byteBuffer.getInt();
        System.out.println(anInt);
    }
}
