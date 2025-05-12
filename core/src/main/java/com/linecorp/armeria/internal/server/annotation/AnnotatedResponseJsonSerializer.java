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

import static com.linecorp.armeria.internal.server.annotation.AnnotatedRequestJsonSerializer.handleInternalTypes;
import static com.linecorp.armeria.internal.server.annotation.AnnotatedRequestJsonSerializer.maybeUnwrapFuture;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import com.linecorp.armeria.common.logging.FieldMasker;

final class AnnotatedResponseJsonSerializer extends JsonSerializer<AnnotatedResponse> {

    private final BeanFieldMaskerCache fieldMaskerCache;

    AnnotatedResponseJsonSerializer(BeanFieldMaskerCache fieldMaskerCache) {
        this.fieldMaskerCache = fieldMaskerCache;
    }

    @Override
    public Class<AnnotatedResponse> handledType() {
        return AnnotatedResponse.class;
    }

    @Override
    public void serialize(AnnotatedResponse value, JsonGenerator gen, SerializerProvider serializers)
            throws IOException {
        gen.writeStartObject();
        gen.writeFieldName("value");
        Object retValue = value.value();
        retValue = maybeUnwrapFuture(retValue);
        if (retValue == null) {
            serializers.defaultSerializeNull(gen);
            gen.writeEndObject();
            return;
        }

        final FieldMasker fieldMasker = fieldMaskerCache.fieldMasker(value.beanFieldInfo());
        retValue = fieldMasker.mask(retValue);
        retValue = handleInternalTypes(retValue);
        if (retValue == null) {
            serializers.defaultSerializeNull(gen);
            gen.writeEndObject();
            return;
        }
        gen.writeObject(retValue);
        gen.writeEndObject();
    }
}
