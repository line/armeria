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

import static com.linecorp.armeria.internal.server.annotation.AnnotatedServiceLogUtil.customizeWellKnownSerializers;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.logging.BeanFieldMaskerSelector;
import com.linecorp.armeria.internal.common.logging.FieldMaskerSelectorProvider;

/**
 * A {@link FieldMaskerSelectorProvider} implementation that supports masking for POJO data types.
 */
@UnstableApi
public final class BeanFieldMaskerSelectorProvider
        implements FieldMaskerSelectorProvider<BeanFieldMaskerSelector> {

    @Override
    public Class<BeanFieldMaskerSelector> supportedType() {
        return BeanFieldMaskerSelector.class;
    }

    @Override
    public void customize(List<BeanFieldMaskerSelector> selectors, ObjectMapper objectMapper) {
        final SimpleModule module = new SimpleModule("bean-field-masker-selector");
        final BeanFieldMaskerCache maskerCache = new BeanFieldMaskerCache(selectors);
        module.setSerializerModifier(new MaskingBeanSerializerModifier(maskerCache));
        module.setDeserializerModifier(new MaskingBeanDeserializerModifier(maskerCache));
        module.addSerializer(new AnnotatedRequestJsonSerializer(maskerCache));
        module.addSerializer(new AnnotatedResponseJsonSerializer(maskerCache));
        module.addSerializer(RpcRequestSerializer.INSTANCE);
        module.addSerializer(RpcResponseSerializer.INSTANCE);
        customizeWellKnownSerializers(module);
        objectMapper.registerModule(module);
        // default to logging "{}" instead of throwing an exception for beans not intended for jackson
        objectMapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    }
}
