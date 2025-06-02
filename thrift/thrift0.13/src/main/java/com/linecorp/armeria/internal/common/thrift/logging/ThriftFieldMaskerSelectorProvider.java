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

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.thrift.TBase;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleDeserializers;
import com.fasterxml.jackson.databind.module.SimpleModule;

import com.linecorp.armeria.common.thrift.logging.ThriftFieldMaskerSelector;
import com.linecorp.armeria.internal.common.logging.FieldMaskerSelectorProvider;

public final class ThriftFieldMaskerSelectorProvider
        implements FieldMaskerSelectorProvider<ThriftFieldMaskerSelector> {

    @Override
    public Class<ThriftFieldMaskerSelector> supportedType() {
        return ThriftFieldMaskerSelector.class;
    }

    @Override
    public void customize(List<ThriftFieldMaskerSelector> selectors, ObjectMapper objectMapper) {
        final SimpleModule module = new SimpleModule("thrift-serde");
        final TBaseSelectorCache selectorCache = new TBaseSelectorCache(selectors);
        module.addSerializer(new TBaseSerializer(selectorCache));
        module.setDeserializers(new TBaseDeserializers(selectorCache));
        objectMapper.registerModule(module);
    }

    private static class TBaseDeserializers extends SimpleDeserializers {

        private static final long serialVersionUID = -1334570127816081095L;
        private static final Map<JavaType, JsonDeserializer<?>> deserializerCache = new ConcurrentHashMap<>();

        private final TBaseSelectorCache selectorCache;

        TBaseDeserializers(TBaseSelectorCache selectorCache) {
            this.selectorCache = selectorCache;
        }

        @Override
        public JsonDeserializer<?> findBeanDeserializer(JavaType type, DeserializationConfig config,
                                                        BeanDescription beanDesc) throws JsonMappingException {
            if (type.isTypeOrSubTypeOf(TBase.class)) {
                @SuppressWarnings({"rawtypes", "unchecked"})
                final Class<? extends TBase> tbaseType = (Class<? extends TBase>) type.getRawClass();
                return deserializerCache
                        .computeIfAbsent(type, ignored -> new TJsonDeserializer(tbaseType, selectorCache));
            }
            return super.findBeanDeserializer(type, config, beanDesc);
        }
    }
}
