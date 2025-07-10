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

package com.linecorp.armeria.common.thrift.logging;

import java.util.function.Function;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.logging.FieldMasker;
import com.linecorp.armeria.common.logging.FieldMaskerSelector;

/**
 * A {@link FieldMaskerSelector} implementation for masking thrift struct types.
 * A simple use-case may look like the following:
 * <pre>{@code
 *
 * ThriftFieldMaskSelector selector =
 *         ThriftFieldMaskSelector.of(fieldInfo -> {
 *             Map<String, String> annotations = info.fieldMetaData().getFieldAnnotations()
 *             if (!annotations.containsKey("sensitive")) {
 *                 return FieldMasker.fallthrough();
 *             }
 *             return FieldMasker.nullify();
 *         });
 * }</pre>
 * Note that the {@code annotations_as_metadata} thrift compiler option must be used to use field annotations.
 * Additionally, {@code typedef} fields may not be serialized or deserialized correctly when using thrift
 * runtime with a version less than 0.19.
 */
@UnstableApi
@FunctionalInterface
public interface ThriftFieldMaskerSelector extends FieldMaskerSelector<ThriftFieldInfo> {

    /**
     * A helper method to create a {@link ThriftFieldMaskerSelector}.
     */
    static ThriftFieldMaskerSelector of(Function<ThriftFieldInfo, FieldMasker> fieldMaskerFunction) {
        return fieldMaskerFunction::apply;
    }

    /**
     * Delegates {@link FieldMasker} selection to a different {@link ThriftFieldMaskerSelector}
     * if the current {@link ThriftFieldMaskerSelector} returns {@link FieldMasker#fallthrough()}.
     */
    default ThriftFieldMaskerSelector orElse(ThriftFieldMaskerSelector other) {
        return fieldInfo -> {
            final FieldMasker fieldMasker = fieldMasker(fieldInfo);
            if (fieldMasker != FieldMasker.fallthrough()) {
                return fieldMasker;
            }
            return other.fieldMasker(fieldInfo);
        };
    }
}
