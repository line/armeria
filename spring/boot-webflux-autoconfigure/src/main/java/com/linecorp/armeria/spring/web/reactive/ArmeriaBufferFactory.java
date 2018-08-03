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

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.core.io.buffer.NettyDataBufferFactory;

import com.linecorp.armeria.common.HttpData;

import io.netty.buffer.UnpooledByteBufAllocator;

/**
 * A factory which extends a {@link DataBufferFactory}, in order to support type conversions
 * between an {@link HttpData} and a {@link DataBuffer}.
 */
public interface ArmeriaBufferFactory extends DataBufferFactory {

    /**
     * A default instance which is generally used when there is no {@link DataBufferFactory}
     * defined as a bean.
     */
    ArmeriaBufferFactory DEFAULT = of(new NettyDataBufferFactory(UnpooledByteBufAllocator.DEFAULT));

    /**
     * Creates an {@link ArmeriaBufferFactory} with the specified {@link DataBufferFactory}.
     */
    static ArmeriaBufferFactory of(DataBufferFactory factory) {
        requireNonNull(factory, "factory");
        if (factory instanceof NettyDataBufferFactory) {
            return new NettyBasedArmeriaBufferFactory((NettyDataBufferFactory) factory);
        }
        if (factory instanceof DefaultDataBufferFactory) {
            return new ByteBufferBasedArmeriaBufferFactory((DefaultDataBufferFactory) factory);
        }
        throw new IllegalArgumentException(
                "Unsupported factory class: " + factory.getClass().getSimpleName());
    }

    /**
     * Converts an {@link HttpData} into a {@link DataBuffer}.
     */
    DataBuffer wrap(HttpData httpData);

    /**
     * Converts a {@link DataBuffer} into an {@link HttpData}.
     */
    HttpData unwrap(DataBuffer dataBuffer);
}
