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
package com.linecorp.armeria.spring.web.reactive;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.core.io.buffer.NettyDataBuffer;
import org.springframework.core.io.buffer.NettyDataBufferFactory;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.spring.internal.common.DataBufferFactoryWrapper;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;

class DataBufferFactoryWrapperTest {

    @Test
    void usingNettyDataBufferFactory_PooledHttpData() {
        final DataBufferFactoryWrapper<?> wrapper =
                new DataBufferFactoryWrapper<>(new NettyDataBufferFactory(UnpooledByteBufAllocator.DEFAULT));

        final HttpData httpData1 =
                HttpData.wrap(Unpooled.wrappedBuffer("abc".getBytes()));

        final DataBuffer buffer = wrapper.toDataBuffer(httpData1);
        assertThat(buffer).isInstanceOf(NettyDataBuffer.class);
        assertThat(((NettyDataBuffer) buffer).getNativeBuffer().refCnt()).isOne();

        final HttpData httpData2 = wrapper.toHttpData(buffer);
        assertThat(httpData2).isInstanceOf(HttpData.class);
        assertThat(httpData2.byteBuf().array()).isSameAs(((NettyDataBuffer) buffer).getNativeBuffer().array());
        assertThat(httpData2.byteBuf().refCnt()).isOne();
    }

    @Test
    void usingNettyDataBufferFactory_HttpData() {
        final DataBufferFactoryWrapper<?> wrapper =
                new DataBufferFactoryWrapper<>(new NettyDataBufferFactory(UnpooledByteBufAllocator.DEFAULT));

        final HttpData httpData1 = HttpData.ofUtf8("abc");

        final DataBuffer buffer = wrapper.toDataBuffer(httpData1);
        assertThat(buffer).isInstanceOf(NettyDataBuffer.class);
        assertThat(((NettyDataBuffer) buffer).getNativeBuffer().refCnt()).isOne();

        final HttpData httpData2 = wrapper.toHttpData(buffer);
        assertThat(httpData2).isInstanceOf(HttpData.class);
        assertThat(httpData2.byteBuf().array()).isSameAs(((NettyDataBuffer) buffer).getNativeBuffer().array());
        assertThat(httpData2.byteBuf().refCnt()).isOne();
    }

    @Test
    public void usingDefaultDataBufferFactory_PooledHttpData() {
        final DataBufferFactoryWrapper<?> wrapper =
                new DataBufferFactoryWrapper<>(new DefaultDataBufferFactory());

        final ByteBuf byteBuf = Unpooled.wrappedBuffer("abc".getBytes());
        final HttpData httpData1 = HttpData.wrap(byteBuf);

        final DataBuffer buffer = wrapper.toDataBuffer(httpData1);
        assertThat(buffer).isInstanceOf(DefaultDataBuffer.class);
        assertThat(byteBuf.refCnt()).isZero();
        assertThat(buffer.asByteBuffer()).isEqualTo(ByteBuffer.wrap("abc".getBytes()));

        final HttpData httpData2 = wrapper.toHttpData(buffer);
        assertThat(httpData2).isInstanceOf(HttpData.class);
        assertThat(httpData2.byteBuf().refCnt()).isOne();
        assertThat(ByteBufUtil.getBytes(httpData2.byteBuf())).isEqualTo("abc".getBytes());
    }

    @Test
    void usingDefaultDataBufferFactory_HttpData() {
        final DataBufferFactoryWrapper<?> wrapper =
                new DataBufferFactoryWrapper<>(new DefaultDataBufferFactory());

        final HttpData httpData1 = HttpData.ofUtf8("abc");

        final DataBuffer buffer = wrapper.toDataBuffer(httpData1);
        assertThat(buffer).isInstanceOf(DefaultDataBuffer.class);
        assertThat(buffer.asByteBuffer()).isEqualTo(ByteBuffer.wrap("abc".getBytes()));

        final HttpData httpData2 = wrapper.toHttpData(buffer);
        assertThat(httpData2).isInstanceOf(HttpData.class);
        assertThat(httpData2.byteBuf().refCnt()).isOne();
        assertThat(ByteBufUtil.getBytes(httpData2.byteBuf())).isEqualTo("abc".getBytes());
    }
}
