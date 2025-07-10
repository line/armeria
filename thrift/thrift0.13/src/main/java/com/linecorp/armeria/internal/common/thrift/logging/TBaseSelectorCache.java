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

import org.apache.thrift.TFieldIdEnum;
import org.apache.thrift.meta_data.FieldMetaData;

import com.linecorp.armeria.common.logging.FieldMasker;
import com.linecorp.armeria.common.thrift.logging.ThriftFieldInfo;
import com.linecorp.armeria.common.thrift.logging.ThriftFieldMaskerSelector;

final class TBaseSelectorCache {

    private final Map<TFieldIdEnum, FieldMasker> cache = new ConcurrentHashMap<>();
    private final List<ThriftFieldMaskerSelector> selectors;

    TBaseSelectorCache(List<ThriftFieldMaskerSelector> selectors) {
        this.selectors = selectors;
    }

    FieldMasker getMapper(TFieldIdEnum fieldId, FieldMetaData fieldMetaData) {
        return cache.computeIfAbsent(fieldId, ignored -> {
            final ThriftFieldInfo holder = new DefaultThriftFieldInfo(fieldMetaData);
            for (ThriftFieldMaskerSelector selector : selectors) {
                final FieldMasker masker = selector.fieldMasker(holder);
                if (masker != null) {
                    return masker;
                }
            }
            return FieldMasker.noMask();
        });
    }
}
