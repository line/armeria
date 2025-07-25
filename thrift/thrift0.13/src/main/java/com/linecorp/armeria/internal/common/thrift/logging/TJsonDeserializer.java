/*
 * Copyright 2025 LY Corporation
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

package com.linecorp.armeria.internal.common.thrift.logging;

import java.io.IOException;

import org.apache.thrift.TBase;
import org.apache.thrift.TException;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import com.linecorp.armeria.common.thrift.ThriftProtocolFactories;

final class TJsonDeserializer extends JsonDeserializer<TBase> {

    private final TBase defaultInstance;
    private final TBaseSelectorCache selectorCache;

    TJsonDeserializer(Class<? extends TBase> clazz, TBaseSelectorCache selectorCache) {
        this.selectorCache = selectorCache;
        defaultInstance = TBaseCache.INSTANCE.newInstance(clazz);
    }

    @Override
    public Class<TBase> handledType() {
        return TBase.class;
    }

    @Override
    public TBase deserialize(JsonParser p, DeserializationContext ctxt)
            throws IOException {
        try {
            final TMaskingDeserializer deserializer =
                    new TMaskingDeserializer(ThriftProtocolFactories.json(), selectorCache);
            final String rawJson = p.readValueAsTree().toString();
            final TBase<?, ?> copied = defaultInstance.deepCopy();
            deserializer.fromString(copied, rawJson);
            return copied;
        } catch (TException e) {
            throw new RuntimeException(e);
        }
    }
}
