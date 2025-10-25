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

import static com.google.common.base.Preconditions.checkState;
import static com.linecorp.armeria.internal.common.logging.MaskerAttributeKeys.REQUEST_CONTEXT_KEY;

import java.io.IOException;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import com.fasterxml.jackson.databind.ser.ContextualSerializer;
import com.fasterxml.jackson.databind.ser.ResolvableSerializer;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.logging.FieldMasker;

final class MaskingBeanSerializerModifier extends BeanSerializerModifier {

    private static final long serialVersionUID = -3932539093396454693L;

    private final BeanFieldMaskerCache fieldMaskerCache;

    MaskingBeanSerializerModifier(BeanFieldMaskerCache fieldMaskerCache) {
        this.fieldMaskerCache = fieldMaskerCache;
    }

    @Override
    public JsonSerializer<?> modifySerializer(SerializationConfig config, BeanDescription beanDesc,
                                              JsonSerializer<?> serializer) {
        return new MaskingJsonSerializer<>(serializer, fieldMaskerCache, beanDesc, null);
    }

    private static final class MaskingJsonSerializer<T> extends JsonSerializer<T>
            implements ContextualSerializer, ResolvableSerializer {

        private final JsonSerializer<T> delegate;
        private final BeanFieldMaskerCache fieldMaskerCache;
        private final BeanDescription classBean;
        @Nullable
        private final BeanProperty property;
        private final FieldMasker mapper;

        MaskingJsonSerializer(JsonSerializer<T> delegate, BeanFieldMaskerCache fieldMaskerCache,
                              BeanDescription classBean, @Nullable BeanProperty property) {
            this.delegate = delegate;
            this.fieldMaskerCache = fieldMaskerCache;
            this.classBean = classBean;
            this.property = property;
            if (property != null) {
                mapper = fieldMaskerCache.fieldMasker(new JacksonBeanFieldInfo(classBean, property));
            } else {
                mapper = FieldMasker.noMask();
            }
        }

        @Override
        public Class<T> handledType() {
            return delegate.handledType();
        }

        @Override
        public void serialize(T o, JsonGenerator jsonGenerator, SerializerProvider serializerProvider)
                throws IOException {
            final RequestContext ctx = (RequestContext) serializerProvider.getAttribute(REQUEST_CONTEXT_KEY);
            final Object masked = mapper.mask(ctx, o);
            if (masked == null) {
                serializerProvider.defaultSerializeNull(jsonGenerator);
                return;
            }
            final Class<?> outClass = mapper.mappedClass(o.getClass());
            if (outClass != o.getClass()) {
                jsonGenerator.writeObject(masked);
                return;
            }
            @SuppressWarnings("unchecked")
            final T t = (T) masked;
            delegate.serialize(t, jsonGenerator, serializerProvider);
        }

        @Override
        public JsonSerializer<?> createContextual(SerializerProvider prov, BeanProperty property)
                throws JsonMappingException {
            checkState(this.property == null);
            return new MaskingJsonSerializer<>(delegate, fieldMaskerCache, classBean, property);
        }

        @Override
        public void resolve(SerializerProvider provider) throws JsonMappingException {
            if (delegate instanceof ResolvableSerializer) {
                ((ResolvableSerializer) delegate).resolve(provider);
            }
        }
    }
}
