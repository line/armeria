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
import java.util.List;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;

import com.linecorp.armeria.common.DefaultHttpData;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.unsafe.ByteBufHttpData;

/**
 * An abstract factory class which helps writing an {@link ArmeriaBufferFactory} class.
 */
abstract class AbstractArmeriaBufferFactory<T extends DataBufferFactory>
        implements ArmeriaBufferFactory {

    private final T delegate;

    protected AbstractArmeriaBufferFactory(T delegate) {
        this.delegate = requireNonNull(delegate, "delegate");
    }

    @Override
    public DataBuffer allocateBuffer() {
        return delegate.allocateBuffer();
    }

    @Override
    public DataBuffer allocateBuffer(int initialCapacity) {
        return delegate.allocateBuffer(initialCapacity);
    }

    @Override
    public DataBuffer join(List<? extends DataBuffer> dataBuffers) {
        return delegate.join(dataBuffers);
    }

    @Override
    public DataBuffer wrap(ByteBuffer byteBuffer) {
        return delegate.wrap(byteBuffer);
    }

    @Override
    public DataBuffer wrap(byte[] bytes) {
        return delegate.wrap(bytes);
    }

    @Override
    public final DataBuffer wrap(HttpData httpData) {
        requireNonNull(httpData, "httpData");
        if (httpData instanceof ByteBufHttpData) {
            return wrap0((ByteBufHttpData) httpData);
        }
        if (httpData instanceof DefaultHttpData) {
            return wrap0((DefaultHttpData) httpData);
        }

        // Should never reach here.
        throw new Error("Unsupported type of " + HttpData.class.getSimpleName() +
                        ": " + httpData.getClass().getSimpleName());
    }

    protected abstract DataBuffer wrap0(ByteBufHttpData httpData);

    protected abstract DataBuffer wrap0(DefaultHttpData httpData);

    protected final T delegate() {
        return delegate;
    }
}
