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

import com.fasterxml.jackson.databind.ObjectMapper;
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
        objectMapper.registerModule(module);
    }
}
