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
 * under the License
 */

package com.linecorp.armeria.internal.common;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;

import com.linecorp.armeria.internal.common.stream.ByteBufDecoderInput;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;

public class ByteBufDecoderInputBenchmark {

    @State(Scope.Thread)
    public static class ByteBufData {

        List<ByteBuf> byteBufs;
        ByteBufDecoderInput input;

        @Setup(Level.Invocation)
        public void setup() {
            byteBufs = new ArrayList<>(100);
            for (int i = 1; i <= 100; i++) {
                final byte[] bytes = new byte[i];
                Arrays.fill(bytes, (byte) i);
                final ByteBuf byteBuf = Unpooled.copiedBuffer(bytes);
                byteBufs.add(byteBuf);
                input = new ByteBufDecoderInput(ByteBufAllocator.DEFAULT);
                input.add(byteBuf.retainedDuplicate());
            }
        }

        @TearDown(Level.Invocation)
        public void doTearDown() {
            for (ByteBuf byteBuf : byteBufs) {
                byteBuf.release();
            }
            input.close();
        }
    }

    @Benchmark
    public void add(ByteBufData data, Blackhole bh) {
        final ByteBufDecoderInput input = new ByteBufDecoderInput(ByteBufAllocator.DEFAULT);
        for (ByteBuf byteBuf : data.byteBufs) {
            bh.consume(input.add(byteBuf));
        }
    }

    @Benchmark
    public void readInt(ByteBufData data, Blackhole bh) {
        final ByteBufDecoderInput input = data.input;
        while (input.readableBytes() >= 4) {
            bh.consume(input.readInt());
        }
    }

    @Benchmark
    public void readByte(ByteBufData data, Blackhole bh) {
        final ByteBufDecoderInput input = data.input;
        while (input.isReadable()) {
            bh.consume(input.readByte());
        }
    }
}
