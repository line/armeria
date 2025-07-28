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

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Map;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.logging.FieldMasker;

/**
 * A {@link ThriftFieldMaskerSelector} builder which allows users to
 * specify {@link FieldMasker}s based on thrift annotations.
 * Rules will be applied sequentially, and the first matching rule's {@link FieldMasker} will be used.
 * Note that the {@code annotations_as_metadata} thrift compiler option must be used to use field annotations.
 <pre>{@code
 * val selector = ThriftFieldMaskerSelector
 *   .buider()
 *   .onFieldAnnotation("secret", FieldMasker.nullify())
 *   .onFieldAnnotation("grade", "red", new CustomFieldMasker())
 *   ...
 *   .build();
 * }</pre>
 */
@UnstableApi
public final class ThriftFieldMaskerSelectorBuilder {

    private final ImmutableList.Builder<AnnotationAndMasker> rulesBuilder = ImmutableList.builder();

    ThriftFieldMaskerSelectorBuilder() {}

    /**
     * Applies {@link FieldMasker} to a field if the field annotation is present.
     */
    public ThriftFieldMaskerSelectorBuilder onFieldAnnotation(String annotation, FieldMasker fieldMasker) {
        requireNonNull(annotation, "annotation");
        requireNonNull(fieldMasker, "fieldMasker");
        rulesBuilder.add(new AnnotationAndMasker(annotation, null, fieldMasker));
        return this;
    }

    /**
     * Applies {@link FieldMasker} to a field if the field annotation is present
     * with exactly the specified value.
     */
    public ThriftFieldMaskerSelectorBuilder onFieldAnnotation(String annotation, String value,
                                                              FieldMasker fieldMasker) {
        requireNonNull(annotation, "annotation");
        requireNonNull(value, "value");
        requireNonNull(fieldMasker, "fieldMasker");
        rulesBuilder.add(new AnnotationAndMasker(annotation, value, fieldMasker));
        return this;
    }

    /**
     * Builds the {@link ThriftFieldMaskerSelector} using the specified rules.
     * If a field doesn't match any of the rules {@link FieldMasker#fallthrough()} will be returned.
     */
    public ThriftFieldMaskerSelector build() {
        final List<AnnotationAndMasker> rules = rulesBuilder.build();
        return new ThriftFieldMaskerSelector() {
            @Override
            public FieldMasker fieldMasker(ThriftFieldInfo info) {
                final Map<String, String> annotations = info.fieldMetaData().getFieldAnnotations();
                for (AnnotationAndMasker rule : rules) {
                    if (rule.matches(annotations)) {
                        return rule.masker;
                    }
                }
                return FieldMasker.fallthrough();
            }
        };
    }

    static final class AnnotationAndMasker {

        private final String annotation;
        @Nullable
        private final String value;
        private final FieldMasker masker;

        private AnnotationAndMasker(String annotation, @Nullable String value, FieldMasker masker) {
            this.annotation = annotation;
            this.value = value;
            this.masker = masker;
        }

        private boolean matches(Map<String, String> fieldAnnotations) {
            if (value == null) {
                return fieldAnnotations.containsKey(annotation);
            }
            return value.equals(fieldAnnotations.get(annotation));
        }
    }
}
