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

import java.io.IOException;
import java.util.function.BiFunction;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.BeanFieldInfo;

final class AnnotatedRequestJsonSerializer extends JsonSerializer<AnnotatedRequest> {

    private final BiFunction<BeanFieldInfo, Object, @Nullable Object> masker;

    AnnotatedRequestJsonSerializer(BeanFieldMaskerCache fieldMaskerCache) {
        masker = (info, o) -> fieldMaskerCache.fieldMasker(info).mask(o);
    }

    AnnotatedRequestJsonSerializer() {
        masker = (info, o) -> o;
    }

    @Override
    public Class<AnnotatedRequest> handledType() {
        return AnnotatedRequest.class;
    }

    @Override
    public void serialize(AnnotatedRequest value, JsonGenerator gen, SerializerProvider serializers)
            throws IOException {
        gen.writeStartObject();
        gen.writeFieldName("params");
        gen.writeStartArray();
        for (int i = 0; i < value.rawParameters().size(); i++) {
            Object parameter = value.getParameter(i);
            if (parameter == null) {
                gen.writeNull();
                continue;
            }
            final BeanFieldInfo beanFieldInfo = value.beanFieldInfos().get(i);
            parameter = masker.apply(beanFieldInfo, parameter);
            if (parameter == null) {
                gen.writeNull();
                continue;
            }
            gen.writeObject(parameter);
        }
        gen.writeEndArray();
        gen.writeEndObject();
    }
}
