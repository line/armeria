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

import java.util.List;
import java.util.function.Function;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A builder which provides a type-safe way to create a {@link FieldMasker}.
 */
@UnstableApi
public final class FieldMaskerBuilder {

    private final ImmutableList.Builder<TypedMasker> maskersBuilder = ImmutableList.builder();

    FieldMaskerBuilder() {}

    /**
     * Adds a type-safe masker to handle the specified class type.
     * Given a field, each masker will sequentially check whether the field value is an
     * instance of the supplied {@param clazz}. If an appropriate {@link FieldMasker}
     * is found, the masker will be applied to the field value.
     */
    public <T> FieldMaskerBuilder addMasker(Class<T> clazz, Function<T, T> masker) {
        requireNonNull(clazz, "clazz");
        requireNonNull(masker, "masker");
        maskersBuilder.add(new TypedMasker(clazz, new FieldMasker() {
            @Nullable
            @Override
            public Object mask(Object obj) {
                //noinspection unchecked
                return masker.apply((T) obj);
            }
        }));
        return this;
    }

    /**
     * Adds a type-safe masker to encrypt a typed value to a {@link String}.
     * This may be useful if users would like to encrypt and decrypt a typed value to a string
     * via symmetric encryption.
     */
    public <T> FieldMaskerBuilder addMasker(Class<T> clazz, Function<T, String> encryptorFunction,
                                            Function<String, T> decryptorFunction) {
        requireNonNull(clazz, "clazz");
        requireNonNull(encryptorFunction, "encryptorFunction");
        requireNonNull(decryptorFunction, "decryptorFunction");
        maskersBuilder.add(new TypedMasker(clazz, new FieldMasker() {
            @Nullable
            @Override
            public Object mask(Object obj) {
                return encryptorFunction.apply((T) obj);
            }

            @Override
            public Object unmask(Object obj, Class<?> expected) {
                return decryptorFunction.apply((String) obj);
            }

            @Override
            public Class<?> mappedClass(Class<?> clazz) {
                return String.class;
            }
        }));
        return this;
    }

    /**
     * Builds the {@link FieldMasker}.
     */
    public FieldMasker build() {
        return build(FieldMasker.noMask());
    }

    /**
     * Builds the {@link FieldMasker}. The specified {@param defaultMasker} will be used if
     * none of the maskers added by {@link #addMasker(Class, Function)} can handle the field value.
     */
    public FieldMasker build(FieldMasker defaultMasker) {
        requireNonNull(defaultMasker, "defaultMasker");
        final List<TypedMasker> maskers = maskersBuilder.build();
        return new CompositeFieldMasker(maskers, defaultMasker);
    }

    private static final class TypedMasker {

        private final Class<?> clazz;
        private final FieldMasker masker;

        TypedMasker(Class<?> clazz, FieldMasker masker) {
            this.clazz = clazz;
            this.masker = masker;
        }

        Class<?> clazz() {
            return clazz;
        }

        public FieldMasker masker() {
            return masker;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("clazz", clazz)
                              .add("masker", masker)
                              .toString();
        }
    }

    private static final class CompositeFieldMasker implements FieldMasker {

        private final List<TypedMasker> maskers;
        private final FieldMasker defaultMasker;

        private CompositeFieldMasker(List<TypedMasker> maskers, FieldMasker defaultMasker) {
            this.maskers = maskers;
            this.defaultMasker = defaultMasker;
        }

        @Nullable
        @Override
        public Object mask(@Nullable RequestContext ctx, Object obj) {
            for (TypedMasker masker : maskers) {
                if (masker.clazz().isInstance(obj)) {
                    return masker.masker().mask(ctx, obj);
                }
            }
            return defaultMasker.mask(ctx, obj);
        }

        @Nullable
        @Override
        public Object mask(Object obj) {
            for (TypedMasker masker : maskers) {
                if (masker.clazz().isInstance(obj)) {
                    return masker.masker().mask(obj);
                }
            }
            return defaultMasker.mask(obj);
        }

        @Override
        public Object unmask(Object obj, Class<?> expected) {
            for (TypedMasker masker : maskers) {
                if (masker.clazz().isAssignableFrom(expected)) {
                    return masker.masker().unmask(obj, expected);
                }
            }
            return defaultMasker.unmask(obj, expected);
        }

        @Override
        public Class<?> mappedClass(Class<?> clazz) {
            for (TypedMasker masker : maskers) {
                if (masker.clazz().isAssignableFrom(clazz)) {
                    return masker.masker().mappedClass(clazz);
                }
            }
            return defaultMasker.mappedClass(clazz);
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("maskers", maskers)
                              .add("defaultMasker", defaultMasker)
                              .toString();
        }
    }
}
