/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.armeria.common.thrift;

import java.util.Set;

import com.google.common.collect.ImmutableSet;

/**
 * Default registered {@link ThriftProtocolFactoryProvider}.
 * It is not overridable but you may provide and register another implementation.
 */
public final class DefaultThriftProtocolFactoryProvider implements ThriftProtocolFactoryProvider {
    @Override
    public Set<ThriftSerializationFormat> thriftSerializationFormats() {
        return ImmutableSet.of(
                new ThriftSerializationFormat(
                        ThriftSerializationFormats.BINARY, ThriftProtocolFactories.BINARY),
                new ThriftSerializationFormat(
                        ThriftSerializationFormats.COMPACT, ThriftProtocolFactories.COMPACT),
                new ThriftSerializationFormat(
                        ThriftSerializationFormats.JSON, ThriftProtocolFactories.JSON),
                new ThriftSerializationFormat(
                        ThriftSerializationFormats.TEXT, ThriftProtocolFactories.TEXT),
                new ThriftSerializationFormat(
                        ThriftSerializationFormats.TEXT_NAMED_ENUM, ThriftProtocolFactories.TEXT_NAMED_ENUM)
        );
    }
}
