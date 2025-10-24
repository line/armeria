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

import java.util.function.Function;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A {@link FieldMaskerSelector} determines whether each field should be masked based on the provided
 * field information {@link T}. This provides a way for users to use cross-cutting concerns (such
 * as annotations) to determine whether a field should be masked.
 *
 * <p>Given a schema, {@link FieldMaskerSelector#fieldMasker(T)} is invoked for each
 * field recursively. If a {@link FieldMasker} other than {@link FieldMasker#fallthrough()}
 * is returned, the {@link FieldMasker} is "bound" to a field and is responsible for masking
 * the field from that point.
 * <pre>{@code
 * BeanFieldMaskerSelector selector = fieldInfo -> {
 *     if (hasAnnotation1(fieldInfo)) {
 *         return fieldMasker1;
 *     }
 *     if (hasAnnotation2(fieldInfo)) {
 *         return fieldMasker2;
 *     }
 *     ...
 * };
 * class Foo {
 *     @Annotation1
 *     public Inner inner; // 'fieldMasker1#mask' will mask this field
 *     @Annotation2
 *     public Inner inner2; // 'fieldMasker2#mask' will mask this field
 * }
 * }</pre>
 * Instead of using this interface directly, users are encouraged to use one of the
 * pre-defined inheritors.
 * @see BeanFieldMaskerSelector
 */
@UnstableApi
@FunctionalInterface
public interface FieldMaskerSelector<T> {

    /**
     * A helper method to create a {@link BeanFieldMaskerSelector}.
     */
    static BeanFieldMaskerSelector ofBean(Function<BeanFieldInfo, FieldMasker> fieldMaskerFunction) {
        return fieldMaskerFunction::apply;
    }

    /**
     * Determines how a field should be masked depending on the returned {@link FieldMasker}.
     * This method will be invoked once per each field.
     * @see FieldMasker
     */
    FieldMasker fieldMasker(T info);
}

