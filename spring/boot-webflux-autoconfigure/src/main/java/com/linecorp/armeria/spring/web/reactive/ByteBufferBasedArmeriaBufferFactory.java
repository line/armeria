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
import java.util.Arrays;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DefaultDataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;

import com.linecorp.armeria.common.DefaultHttpData;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.unsafe.ByteBufHttpData;

import io.netty.buffer.ByteBufUtil;

/**
 * A factory which forwards the method calls defined in {@link DataBufferFactory} to the given
 * {@link DefaultDataBufferFactory} instance.
 */
public class ByteBufferBasedArmeriaBufferFactory
        extends AbstractArmeriaBufferFactory<DefaultDataBufferFactory> {

    ByteBufferBasedArmeriaBufferFactory(DefaultDataBufferFactory delegate) {
        super(delegate);
    }

    @Override
    protected DataBuffer wrap0(ByteBufHttpData httpData) {
        return delegate().wrap(ByteBufUtil.getBytes(httpData.content()));
    }

    @Override
    protected DataBuffer wrap0(DefaultHttpData httpData) {
        if (httpData.offset() == 0) {
            return delegate().wrap(httpData.array());
        }
        return delegate().wrap(Arrays.copyOfRange(httpData.array(), httpData.offset(), httpData.length()));
    }

    @Override
    public HttpData unwrap(DataBuffer dataBuffer) {
        requireNonNull(dataBuffer, "dataBuffer");
        final ByteBuffer src =
                dataBuffer instanceof DefaultDataBuffer ? ((DefaultDataBuffer) dataBuffer).getNativeBuffer()
                                                        : dataBuffer.asByteBuffer();
        if (src.hasArray()) {
            return HttpData.of(src.array());
        }

        final byte[] dst = new byte[src.remaining()];
        src.get(dst);
        return HttpData.of(dst);
    }
}
