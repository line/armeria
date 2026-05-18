/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.common.thrift;

import static java.util.Objects.requireNonNull;

import java.util.function.Function;

import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TProtocolDecorator;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Customizes the {@link TProtocol} used for Thrift message serialization and deserialization.
 */
@UnstableApi
public interface ThriftProtocolDecorator {

    /**
     * Returns the default {@link ThriftProtocolDecorator} without any customization.
     */
    static ThriftProtocolDecorator ofDefault() {
        return new ThriftProtocolDecorator() {
            @Override
            public TProtocol decorateForRequest(RequestContext ctx, TProtocol tProtocol,
                                                SerializationFormat serializationFormat) {
                return tProtocol;
            }

            @Override
            public TProtocol decorateForResponse(RequestContext ctx, TProtocol tProtocol,
                                                 SerializationFormat serializationFormat) {
                return tProtocol;
            }
        };
    }

    /**
     * Returns a {@link ThriftProtocolDecorator} that wraps both request and response protocols
     * using the specified {@link TProtocolDecorator} factory.
     *
     * <p>Please note that {@link TProtocolDecorator} may not correctly work with all protocols.
     *
     * @param decoratorFactory a function that takes a protocol and creates a new {@link TProtocolDecorator}
     */
    static ThriftProtocolDecorator ofTProtocolDecorator(
            Function<TProtocol, ? extends TProtocolDecorator> decoratorFactory) {
        requireNonNull(decoratorFactory, "decoratorFactory");

        return new ThriftProtocolDecorator() {
            @Override
            public TProtocol decorateForRequest(RequestContext ctx, TProtocol tProtocol,
                                                SerializationFormat serializationFormat) {
                return decoratorFactory.apply(tProtocol);
            }

            @Override
            public TProtocol decorateForResponse(RequestContext ctx, TProtocol tProtocol,
                                                 SerializationFormat serializationFormat) {
                return decoratorFactory.apply(tProtocol);
            }
        };
    }

    /**
     * Returns the {@link TProtocol} used for request serialization and deserialization.
     */
    TProtocol decorateForRequest(RequestContext ctx, TProtocol tProtocol,
                                 SerializationFormat serializationFormat);

    /**
     * Returns the {@link TProtocol} used for response serialization and deserialization.
     */
    TProtocol decorateForResponse(RequestContext ctx, TProtocol tProtocol,
                                  SerializationFormat serializationFormat);
}
