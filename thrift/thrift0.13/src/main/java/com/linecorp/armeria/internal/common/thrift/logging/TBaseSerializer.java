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

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import com.linecorp.armeria.common.thrift.ThriftProtocolFactories;

@SuppressWarnings("rawtypes")
final class TBaseSerializer extends JsonSerializer<TBase> {

    private final TBaseSelectorCache selectorCache;

    TBaseSerializer(TBaseSelectorCache selectorCache) {
        this.selectorCache = selectorCache;
    }

    @Override
    public Class<TBase> handledType() {
        return TBase.class;
    }

    @Override
    public void serialize(TBase tBase, JsonGenerator gen, SerializerProvider prov)
            throws IOException {
        try {
            final String serialized =
                    new TMaskingSerializer(ThriftProtocolFactories.json(), selectorCache).toString(tBase);
            gen.writeRaw(serialized);
        } catch (TException e) {
            prov.reportMappingProblem("Failed to serialize TBase: " + tBase, e);
        }
    }
}
