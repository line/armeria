/*
 * Copyright 2015 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.server.docs;

import static java.util.Objects.requireNonNull;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Metadata about an enum type.
 */
@UnstableApi
public final class EnumInfo implements NamedTypeInfo {

    private final String name;
    private final List<EnumValueInfo> values;
    @Nullable
    private final DescriptionInfo descriptionInfo;

    /**
     * Creates a new instance.
     */
    public EnumInfo(Class<? extends Enum<?>> enumType) {
        this(enumType.getName(), enumType);
    }

    /**
     * Creates a new instance.
     */
    public EnumInfo(Class<? extends Enum<?>> enumType, @Nullable DescriptionInfo descriptionInfo) {
        this(enumType.getName(), enumType, descriptionInfo);
    }

    /**
     * Creates a new instance.
     */
    public EnumInfo(String name, Class<? extends Enum<?>> enumType) {
        this(name, enumType, null);
    }

    /**
     * Creates a new instance.
     */
    public EnumInfo(String name, Class<? extends Enum<?>> enumType,
                    @Nullable DescriptionInfo descriptionInfo) {
        this(name, toEnumValues(enumType), descriptionInfo);
    }

    /**
     * Creates a new instance.
     */
    public EnumInfo(String name, Iterable<EnumValueInfo> values) {
        this(name, values, null);
    }

    /**
     * Creates a new instance.
     */
    public EnumInfo(String name, Iterable<EnumValueInfo> values,
                    @Nullable DescriptionInfo descriptionInfo) {
        this.name = requireNonNull(name, "name");
        this.values = ImmutableList.copyOf(requireNonNull(values, "values"));
        this.descriptionInfo = descriptionInfo;
    }

    @Override
    public String name() {
        return name;
    }

    /**
     * Returns the constant values defined by the type.
     */
    @JsonProperty
    public List<EnumValueInfo> values() {
        return values;
    }

    /**
     * Returns the description information of the enum.
     */
    @Override
    public DescriptionInfo descriptionInfo() {
        return descriptionInfo;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof EnumInfo)) {
            return false;
        }

        final EnumInfo that = (EnumInfo) o;
        return name.equals(that.name) && values.equals(that.values);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, values);
    }

    @Override
    public String toString() {
        return name;
    }

    private static Iterable<EnumValueInfo> toEnumValues(Class<? extends Enum<?>> enumType) {
        final Class<?> rawEnumType = requireNonNull(enumType, "enumType");
        @SuppressWarnings({ "unchecked", "rawtypes" })
        final Set<Enum> values = EnumSet.allOf((Class<Enum>) rawEnumType);
        return values.stream().map(e -> new EnumValueInfo(e.name()))::iterator;
    }
}
