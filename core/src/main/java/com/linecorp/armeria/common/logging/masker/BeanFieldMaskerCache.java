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

package com.linecorp.armeria.common.logging.masker;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.List;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

final class BeanFieldMaskerCache {

    private final List<BeanFieldMaskerSelector> selectors;
    private final Cache<BeanFieldInfo, FieldMasker> fieldMaskerCache = Caffeine.newBuilder().build();

    BeanFieldMaskerCache(List<BeanFieldMaskerSelector> selectors) {
        this.selectors = selectors;
    }

    FieldMasker fieldMasker(BeanFieldInfo annotationHolder) {
        final FieldMasker fieldMasker = fieldMaskerCache.get(annotationHolder, holder -> {
            for (BeanFieldMaskerSelector selector : selectors) {
                final FieldMasker masker = selector.fieldMasker(holder);
                checkArgument(masker != null, "%s.fieldMasker() returned null for (%s)",
                              selector.getClass().getName(), holder);
                if (masker != FieldMasker.fallthrough()) {
                    return masker;
                }
            }
            return FieldMasker.noMask();
        });
        assert fieldMasker != null;
        return fieldMasker;
    }
}
