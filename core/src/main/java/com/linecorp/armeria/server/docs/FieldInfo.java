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

import java.util.Objects;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;

/**
 * Metadata about a field of a struct or an exception.
 */
public final class FieldInfo {

    private final String name;
    private final FieldRequirement requirement;
    private final TypeSignature typeSignature;
    private final String docString;

    /**
     * Creates a new instance.
     */
    public FieldInfo(String name, FieldRequirement requirement, TypeSignature typeSignature) {
        this(name, requirement, typeSignature, null);
    }

    /**
     * Creates a new instance.
     */
    public FieldInfo(String name, FieldRequirement requirement,
                     TypeSignature typeSignature, @Nullable String docString) {
        this.name = requireNonNull(name, "name");
        this.requirement = requireNonNull(requirement, "requirement");
        this.typeSignature = requireNonNull(typeSignature, "typeSignature");
        this.docString = Strings.emptyToNull(docString);
    }

    /**
     * Returns the fully qualified type name of the field.
     */
    @JsonProperty
    public String name() {
        return name;
    }

    /**
     * Returns the requirement level of the field.
     */
    @JsonProperty
    public FieldRequirement requirement() {
        return requirement;
    }

    /**
     * Returns the metadata about the type of the field.
     */
    @JsonProperty
    public TypeSignature typeSignature() {
        return typeSignature;
    }

    /**
     * Returns the documentation string of the field.
     */
    @JsonProperty
    @JsonInclude(Include.NON_NULL)
    @Nullable
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

        final FieldInfo that = (FieldInfo) o;
        return name.equals(that.name) &&
               requirement == that.requirement &&
               typeSignature.equals(that.typeSignature);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, requirement, typeSignature);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("name", name)
                          .add("requirement", requirement)
                          .add("typeSignature", typeSignature)
                          .toString();
    }
}
