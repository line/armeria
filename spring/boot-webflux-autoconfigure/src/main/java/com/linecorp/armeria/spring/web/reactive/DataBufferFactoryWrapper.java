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

import static java.util.Objects.requireNonNull;

import java.nio.ByteBuffer;
import java.util.function.Function;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DefaultDataBuffer;
import org.springframework.core.io.buffer.NettyDataBuffer;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import org.springframework.core.io.buffer.PooledDataBuffer;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.unsafe.ByteBufHttpData;

import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;

/**
 * A wrapper of the configured {@link DataBufferFactory}. This wrapper is in charge of converting objects
 * between {@link DataBuffer} of Spring framework and {@link HttpData} of Armeria.
 */
final class DataBufferFactoryWrapper<T extends DataBufferFactory> {

    static final DataBufferFactoryWrapper<NettyDataBufferFactory> DEFAULT =
            new DataBufferFactoryWrapper<>(new NettyDataBufferFactory(PooledByteBufAllocator.DEFAULT));

    private final T delegate;
    private final Function<ByteBufHttpData, DataBuffer> converter;

    DataBufferFactoryWrapper(T delegate) {
        this.delegate = requireNonNull(delegate, "delegate");
        converter = delegate instanceof NettyDataBufferFactory ? this::withNettyDataBufferFactory
                                                               : this::withDataBufferFactory;
    }

    /**
     * Returns the underlying {@link DataBufferFactory}.
     */
    public T delegate() {
        return delegate;
    }

    /**
     * Converts a {@link DataBuffer} into an {@link HttpData}.
     */
    HttpData toHttpData(DataBuffer dataBuffer) {
        if (dataBuffer instanceof NettyDataBuffer) {
            return new ByteBufHttpData(((NettyDataBuffer) dataBuffer).getNativeBuffer(), false);
        }
        if (dataBuffer instanceof DefaultDataBuffer) {
            return new ByteBufHttpData(
                    Unpooled.wrappedBuffer(((DefaultDataBuffer) dataBuffer).getNativeBuffer()), false);
        }
        return new ByteBufHttpData(Unpooled.wrappedBuffer(dataBuffer.asByteBuffer()), false);
    }

    /**
     * Converts an {@link HttpData} into a {@link DataBuffer}.
     */
    DataBuffer toDataBuffer(HttpData httpData) {
        requireNonNull(httpData, "httpData");
        if (!(httpData instanceof ByteBufHttpData)) {
            return delegate.wrap(ByteBuffer.wrap(httpData.array()));
        }
        return converter.apply((ByteBufHttpData) httpData);
    }

    /**
     * Returns a {@link PooledDataBuffer} which will be released after consuming by the consumer.
     * Currently, the {@link NettyDataBuffer} is only one implementation of the {@link PooledDataBuffer}
     * which is exposed to the public API.
     */
    private PooledDataBuffer withNettyDataBufferFactory(ByteBufHttpData data) {
        return ((NettyDataBufferFactory) delegate).wrap(data.content());
    }

    /**
     * Returns a memory-based {@link DataBuffer} which will be garbage-collected.
     */
    private DataBuffer withDataBufferFactory(ByteBufHttpData data) {
        final byte[] dataArray = ByteBufUtil.getBytes(data.content());
        data.release();
        return delegate.wrap(dataArray);
    }
}
