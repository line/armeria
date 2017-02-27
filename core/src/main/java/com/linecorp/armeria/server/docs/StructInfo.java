/*
 * Copyright 2015 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.docs;

import static java.util.Objects.requireNonNull;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;

/**
 * Metadata about a struct type.
 */
public final class StructInfo implements ClassInfo {

    private final String name;
    private final List<FieldInfo> fields;
    private final String docString;

    /**
     * Creates a new instance.
     */
    public StructInfo(String name, Iterable<FieldInfo> fields) {
        this(name, fields, null);
    }

    /**
     * Creates a new instance.
     */
    public StructInfo(String name, Iterable<FieldInfo> fields, @Nullable String docString) {
        this.name = requireNonNull(name, "name");
        this.fields = ImmutableList.copyOf(requireNonNull(fields, "fields"));
        this.docString = docString;
    }

    @Override
    public Type type() {
        return Type.STRUCT;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public List<FieldInfo> fields() {
        return fields;
    }

    @Override
    public List<Object> constants() {
        return Collections.emptyList();
    }

    @Override
    public String docString() {
        return docString;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        final StructInfo that = (StructInfo) o;
        return name.equals(that.name) && fields.equals(that.fields);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type(), name, fields);
    }

    @Override
    public String toString() {
        return name;
    }
}
