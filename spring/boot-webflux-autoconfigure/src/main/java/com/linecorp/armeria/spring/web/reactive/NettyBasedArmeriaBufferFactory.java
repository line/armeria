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

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.NettyDataBuffer;
import org.springframework.core.io.buffer.NettyDataBufferFactory;

import com.linecorp.armeria.common.DefaultHttpData;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.unsafe.ByteBufHttpData;

import io.netty.buffer.ByteBuf;

/**
 * A factory which forwards the method calls defined in {@link DataBufferFactory} to the given
 * {@link NettyDataBufferFactory} instance.
 */
class NettyBasedArmeriaBufferFactory extends AbstractArmeriaBufferFactory<NettyDataBufferFactory> {

    NettyBasedArmeriaBufferFactory(NettyDataBufferFactory delegate) {
        super(delegate);
    }

    @Override
    protected DataBuffer wrap0(ByteBufHttpData httpData) {
        return delegate().wrap(httpData.content());
    }

    @Override
    protected DataBuffer wrap0(DefaultHttpData httpData) {
        return delegate().wrap(ByteBuffer.wrap(httpData.array(), httpData.offset(), httpData.length()));
    }

    @Override
    public HttpData unwrap(DataBuffer dataBuffer) {
        requireNonNull(dataBuffer, "dataBuffer");
        if (dataBuffer instanceof NettyDataBuffer) {
            return new ByteBufHttpData(((NettyDataBuffer) dataBuffer).getNativeBuffer(), false);
        }

        final ByteBuf byteBuf = delegate().getByteBufAllocator().buffer(dataBuffer.readableByteCount());
        byteBuf.writeBytes(dataBuffer.asByteBuffer());
        return new ByteBufHttpData(byteBuf, false);
    }
}
