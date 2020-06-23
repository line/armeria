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

import org.junit.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.core.io.buffer.NettyDataBuffer;
import org.springframework.core.io.buffer.NettyDataBufferFactory;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.unsafe.PooledHttpData;

import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;

public class DataBufferFactoryWrapperTest {

    @Test
    public void usingNettyDataBufferFactory_PooledHttpData() {
        final DataBufferFactoryWrapper<?> wrapper =
                new DataBufferFactoryWrapper<>(new NettyDataBufferFactory(UnpooledByteBufAllocator.DEFAULT));

        final PooledHttpData httpData1 =
                PooledHttpData.wrap(Unpooled.wrappedBuffer("abc".getBytes()));

        final DataBuffer buffer = wrapper.toDataBuffer(httpData1);
        assertThat(buffer).isInstanceOf(NettyDataBuffer.class);
        assertThat(((NettyDataBuffer) buffer).getNativeBuffer().refCnt()).isOne();

        final HttpData httpData2 = wrapper.toHttpData(buffer);
        assertThat(httpData2).isInstanceOf(PooledHttpData.class);
        assertThat(((PooledHttpData) httpData2).content())
                .isEqualTo(((NettyDataBuffer) buffer).getNativeBuffer());
        assertThat(((PooledHttpData) httpData2).refCnt()).isOne();
    }

    @Test
    public void usingNettyDataBufferFactory_HttpData() {
        final DataBufferFactoryWrapper<?> wrapper =
                new DataBufferFactoryWrapper<>(new NettyDataBufferFactory(UnpooledByteBufAllocator.DEFAULT));

        final HttpData httpData1 = HttpData.ofUtf8("abc");

        final DataBuffer buffer = wrapper.toDataBuffer(httpData1);
        assertThat(buffer).isInstanceOf(NettyDataBuffer.class);
        assertThat(((NettyDataBuffer) buffer).getNativeBuffer().refCnt()).isOne();

        final HttpData httpData2 = wrapper.toHttpData(buffer);
        assertThat(httpData2).isInstanceOf(PooledHttpData.class);
        assertThat(((PooledHttpData) httpData2).content())
                .isEqualTo(((NettyDataBuffer) buffer).getNativeBuffer());
        assertThat(((PooledHttpData) httpData2).refCnt()).isOne();
    }

    @Test
    public void usingDefaultDataBufferFactory_PooledHttpData() {
        final DataBufferFactoryWrapper<?> wrapper =
                new DataBufferFactoryWrapper<>(new DefaultDataBufferFactory());

        final PooledHttpData httpData1 =
                PooledHttpData.wrap(Unpooled.wrappedBuffer("abc".getBytes()));

        final DataBuffer buffer = wrapper.toDataBuffer(httpData1);
        assertThat(buffer).isInstanceOf(DefaultDataBuffer.class);
        assertThat(httpData1.refCnt()).isZero();
        assertThat(buffer.asByteBuffer()).isEqualTo(ByteBuffer.wrap("abc".getBytes()));

        final HttpData httpData2 = wrapper.toHttpData(buffer);
        assertThat(httpData2).isInstanceOf(PooledHttpData.class);
        assertThat(((PooledHttpData) httpData2).refCnt()).isOne();
        assertThat(ByteBufUtil.getBytes(((PooledHttpData) httpData2).content())).isEqualTo("abc".getBytes());
    }

    @Test
    public void usingDefaultDataBufferFactory_HttpData() {
        final DataBufferFactoryWrapper<?> wrapper =
                new DataBufferFactoryWrapper<>(new DefaultDataBufferFactory());

        final HttpData httpData1 = HttpData.ofUtf8("abc");

        final DataBuffer buffer = wrapper.toDataBuffer(httpData1);
        assertThat(buffer).isInstanceOf(DefaultDataBuffer.class);
        assertThat(buffer.asByteBuffer()).isEqualTo(ByteBuffer.wrap("abc".getBytes()));

        final HttpData httpData2 = wrapper.toHttpData(buffer);
        assertThat(httpData2).isInstanceOf(PooledHttpData.class);
        assertThat(((PooledHttpData) httpData2).refCnt()).isOne();
        assertThat(ByteBufUtil.getBytes(((PooledHttpData) httpData2).content())).isEqualTo("abc".getBytes());
    }
}
