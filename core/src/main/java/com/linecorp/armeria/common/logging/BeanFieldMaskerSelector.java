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

package com.linecorp.armeria.common.logging;

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A {@link FieldMaskerSelector} implementation for masking POJO data types.
 * A simple use-case may look like the following:
 * <pre>{@code
 * @interface Masker {}
 *
 * class MyPojo {
 *     @Masker
 *     public String hello = "world";
 * }
 *
 * BeanFieldMaskerSelector selector =
 *         FieldMaskerSelector.ofBean(fieldInfo -> {
 *             Masker maskerAnnotation = fieldInfo.getAnnotation(Masker.class);
 *             if (maskerAnnotation == null) {
 *                 return FieldMasker.fallthrough();
 *             }
 *             return FieldMasker.nullify();
 *         });
 * }</pre>
 */
@UnstableApi
@FunctionalInterface
public interface BeanFieldMaskerSelector extends FieldMaskerSelector<BeanFieldInfo> {

    /**
     * Delegates {@link FieldMasker} selection to a different {@link BeanFieldMaskerSelector}
     * if the current {@link BeanFieldMaskerSelector} returns {@link FieldMasker#fallthrough()}.
     */
    default BeanFieldMaskerSelector orElse(BeanFieldMaskerSelector other) {
        requireNonNull(other, "other");
        return beanFieldInfo -> {
            final FieldMasker fieldMasker = fieldMasker(beanFieldInfo);
            if (fieldMasker != FieldMasker.fallthrough()) {
                return fieldMasker;
            }
            return other.fieldMasker(beanFieldInfo);
        };
    }
}
