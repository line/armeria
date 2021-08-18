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

import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Metadata about a field of a struct or an exception.
 *
 * @see FieldInfoBuilder
 */
@UnstableApi
public final class FieldInfo {

    /**
     * Creates a new {@link FieldInfo} with the specified {@code name} and {@link TypeSignature}.
     * The {@link FieldLocation} and {@link FieldRequirement} of the {@link FieldInfo} will be
     * {@code UNSPECIFIED}.
     */
    public static FieldInfo of(String name, TypeSignature typeSignature) {
        return new FieldInfo(name, FieldLocation.UNSPECIFIED, FieldRequirement.UNSPECIFIED, typeSignature,
                             ImmutableList.of(), null);
    }

    /**
     * Returns a newly created {@link FieldInfoBuilder}.
     */
    public static FieldInfoBuilder builder(String name, TypeSignature typeSignature) {
        return new FieldInfoBuilder(name, typeSignature);
    }

    /**
     * Returns a newly created {@link FieldInfoBuilder}.
     */
    public static FieldInfoBuilder builder(String name, TypeSignature typeSignature,
                                           FieldInfo... childFieldInfos) {
        return new FieldInfoBuilder(name, typeSignature, childFieldInfos);
    }

    /**
     * Returns a newly created {@link FieldInfoBuilder}.
     */
    public static FieldInfoBuilder builder(String name, TypeSignature typeSignature,
                                           Iterable<FieldInfo> childFieldInfos) {
        return new FieldInfoBuilder(name, typeSignature, childFieldInfos);
    }

    private final String name;
    private final FieldLocation location;
    private final FieldRequirement requirement;
    private final TypeSignature typeSignature;
    private final List<FieldInfo> childFieldInfos;

    @Nullable
    private final String docString;

    /**
     * Creates a new instance.
     */
    FieldInfo(String name, FieldLocation location, FieldRequirement requirement,
              TypeSignature typeSignature, List<FieldInfo> childFieldInfos, @Nullable String docString) {
        this.name = name;
        this.location = location;
        this.requirement = requirement;
        this.typeSignature = typeSignature;
        this.childFieldInfos = childFieldInfos;
        this.docString = docString;
    }

    /**
     * Returns the fully qualified type name of the field.
     */
    @JsonProperty
    public String name() {
        return name;
    }

    /**
     * Returns the location of the field.
     */
    @JsonProperty
    public FieldLocation location() {
        return location;
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
     * Returns the child field infos of the field.
     */
    @JsonProperty
    public List<FieldInfo> childFieldInfos() {
        return childFieldInfos;
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
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof FieldInfo)) {
            return false;
        }

        final FieldInfo that = (FieldInfo) o;
        return name.equals(that.name) &&
               location == that.location &&
               requirement == that.requirement &&
               typeSignature.equals(that.typeSignature) &&
               childFieldInfos.equals(that.childFieldInfos);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, location, requirement, typeSignature, childFieldInfos);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).omitNullValues()
                          .add("name", name)
                          .add("location", location)
                          .add("requirement", requirement)
                          .add("typeSignature", typeSignature)
                          .add("childFieldInfos", childFieldInfos)
                          .add("docString", docString)
                          .toString();
    }
}
