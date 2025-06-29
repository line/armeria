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

package com.linecorp.armeria.internal.server.annotation;

import static com.google.common.base.Preconditions.checkArgument;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.deser.std.DelegatingDeserializer;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.FieldMasker;

final class MaskingBeanDeserializerModifier extends BeanDeserializerModifier {

    private static final long serialVersionUID = -2068102511617505526L;

    private final BeanFieldMaskerCache maskerCache;

    MaskingBeanDeserializerModifier(BeanFieldMaskerCache maskerCache) {
        this.maskerCache = maskerCache;
    }

    @Override
    public JsonDeserializer<?> modifyDeserializer(DeserializationConfig config, BeanDescription beanDesc,
                                                  JsonDeserializer<?> deserializer) {
        return new MaskingJsonDeserializer(deserializer, beanDesc, null, maskerCache);
    }

    private static final class MaskingJsonDeserializer extends DelegatingDeserializer {

        private static final long serialVersionUID = -9079127442512906109L;

        private final JsonDeserializer<?> delegate;
        private final BeanDescription beanDesc;
        private final BeanFieldMaskerCache maskerCache;
        @Nullable
        private final BeanProperty beanProperty;
        private final FieldMasker mapper;

        MaskingJsonDeserializer(JsonDeserializer<?> delegate, BeanDescription beanDesc,
                                @Nullable BeanProperty beanProperty, BeanFieldMaskerCache maskerCache) {
            super(delegate);
            this.delegate = delegate;
            this.beanDesc = beanDesc;
            this.beanProperty = beanProperty;
            this.maskerCache = maskerCache;
            if (beanProperty != null) {
                mapper = maskerCache.fieldMasker(new JacksonBeanFieldInfo(beanDesc, beanProperty));
            } else {
                mapper = FieldMasker.noMask();
            }
        }

        @Override
        public Object deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            final Class<?> mappedClass = mapper.mappedClass(beanDesc.getBeanClass());
            final Object readValue;
            if (mappedClass != beanDesc.getBeanClass()) {
                readValue = p.readValueAs(mappedClass);
            } else {
                readValue = delegate.deserialize(p, ctxt);
            }
            final Object unmasked = mapper.unmask(readValue, beanDesc.getBeanClass());
            checkArgument(unmasked != null, "Mapper (%s) returned null for: (%s)",
                          mapper, unmasked);
            return unmasked;
        }

        @Override
        protected JsonDeserializer<?> newDelegatingInstance(JsonDeserializer<?> jsonDeserializer) {
            return new MaskingJsonDeserializer(jsonDeserializer, beanDesc, beanProperty, maskerCache);
        }

        @Override
        public JsonDeserializer<?> createContextual(DeserializationContext ctxt, BeanProperty beanProperty)
                throws JsonMappingException {
            final JsonDeserializer<?> contextual = super.createContextual(ctxt, beanProperty);
            return new MaskingJsonDeserializer(contextual, beanDesc, beanProperty, maskerCache);
        }
    }
}
