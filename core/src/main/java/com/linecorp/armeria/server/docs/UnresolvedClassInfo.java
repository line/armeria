/*
 * Copyright 2017 LINE Corporation
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

import java.util.List;
import java.util.Objects;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;

/**
 * Metadata about a struct type, an enum type or an exception whose exact type name or
 * its {@link FieldInfo}s are not resolved.
 */
public final class UnresolvedClassInfo implements ClassInfo {

    private final Type type;
    private final String name;
    private final String docString;

    /**
     * Creates a new instance.
     */
    public UnresolvedClassInfo(Type type, String name) {
        this(type, name, null);
    }

    /**
     * Creates a new instance.
     */
    public UnresolvedClassInfo(Type type, String name, @Nullable String docString) {
        this.type = requireNonNull(type, "type");
        this.name = requireNonNull(name, "name");
        this.docString = docString;
    }

    @Override
    public Type type() {
        return type;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String signature() {
        return '?' + name;
    }

    @Override
    public List<FieldInfo> fields() {
        return ImmutableList.of();
    }

    @Override
    public List<Object> constants() {
        return ImmutableList.of();
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

        final UnresolvedClassInfo that = (UnresolvedClassInfo) o;
        return type == that.type && name.equals(that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type(), name);
    }

    @Override
    public String toString() {
        return name;
    }
}
