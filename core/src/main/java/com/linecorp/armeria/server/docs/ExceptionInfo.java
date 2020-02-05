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

import static java.util.Comparator.comparing;
import static java.util.Objects.requireNonNull;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

import com.linecorp.armeria.common.util.UnstableApi;

/**
 * Metadata about an exception type.
 */
@UnstableApi
public final class ExceptionInfo implements NamedTypeInfo {

    private final String name;
    private final List<FieldInfo> fields;
    @Nullable
    private final String docString;

    /**
     * Creates a new instance.
     */
    public ExceptionInfo(String name, Iterable<FieldInfo> fields) {
        this(name, fields, null);
    }

    /**
     * Creates a new instance.
     */
    public ExceptionInfo(String name, Iterable<FieldInfo> fields, @Nullable String docString) {
        this.name = requireNonNull(name, "name");
        this.fields = ImmutableList.copyOf(requireNonNull(fields, "fields"));
        this.docString = Strings.emptyToNull(docString);
    }

    @Override
    public String name() {
        return name;
    }

    /**
     * Returns the metadata about the fields of the type.
     */
    @JsonProperty
    public List<FieldInfo> fields() {
        return fields;
    }

    @Override
    public String docString() {
        return docString;
    }

    @Override
    public Set<TypeSignature> findNamedTypes() {
        final Set<TypeSignature> collectedNamedTypes = new HashSet<>();
        fields().forEach(f -> ServiceInfo.findNamedTypes(collectedNamedTypes, f.typeSignature()));
        return ImmutableSortedSet.copyOf(comparing(TypeSignature::name), collectedNamedTypes);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof ExceptionInfo)) {
            return false;
        }

        final ExceptionInfo that = (ExceptionInfo) o;
        return name.equals(that.name) && fields.equals(that.fields);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, fields);
    }

    @Override
    public String toString() {
        return name;
    }
}
