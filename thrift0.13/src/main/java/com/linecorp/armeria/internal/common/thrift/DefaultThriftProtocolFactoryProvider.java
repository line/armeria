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
package com.linecorp.armeria.internal.common.thrift;

import java.util.Set;

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.thrift.ThriftProtocolFactories;
import com.linecorp.armeria.common.thrift.ThriftProtocolFactoryProvider;
import com.linecorp.armeria.common.thrift.ThriftSerializationFormats;

/**
 * Default registered {@link ThriftProtocolFactoryProvider}.
 * It is not overridable but you may provide and register another implementation.
 */
public final class DefaultThriftProtocolFactoryProvider extends ThriftProtocolFactoryProvider {
    @Override
    protected Set<Entry> entries() {
        return ImmutableSet.of(
                new Entry(
                        ThriftSerializationFormats.BINARY, ThriftProtocolFactories.BINARY),
                new Entry(
                        ThriftSerializationFormats.COMPACT, ThriftProtocolFactories.COMPACT),
                new Entry(
                        ThriftSerializationFormats.JSON, ThriftProtocolFactories.JSON),
                new Entry(
                        ThriftSerializationFormats.TEXT, ThriftProtocolFactories.TEXT),
                new Entry(
                        ThriftSerializationFormats.TEXT_NAMED_ENUM, ThriftProtocolFactories.TEXT_NAMED_ENUM)
        );
    }
}
