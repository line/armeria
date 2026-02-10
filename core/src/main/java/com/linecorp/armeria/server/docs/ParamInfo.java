/*
 * Copyright 2026 LY Corporation
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

package com.linecorp.armeria.server.docs;

import static java.util.Objects.requireNonNull;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Metadata about a parameter of a method.
 * Unlike {@link FieldInfo}, this class does not contain description information.
 * Descriptions are stored separately in {@link ServiceSpecification#docStrings()}.
 *
 * @see ParamInfoBuilder
 */
@UnstableApi
public final class ParamInfo {

    /**
     * Creates a new {@link ParamInfo} with the specified {@code name} and {@link TypeSignature}.
     * The {@link FieldLocation} and {@link FieldRequirement} of the {@link ParamInfo} will be
     * {@code UNSPECIFIED}.
     */
    public static ParamInfo of(String name, TypeSignature typeSignature) {
        return new ParamInfo(name, FieldLocation.UNSPECIFIED, FieldRequirement.UNSPECIFIED, typeSignature);
    }

    /**
     * Returns a newly created {@link ParamInfoBuilder}.
     */
    public static ParamInfoBuilder builder(String name, TypeSignature typeSignature) {
        return new ParamInfoBuilder(name, typeSignature);
    }

    private final String name;
    private final FieldLocation location;
    private final FieldRequirement requirement;
    private final TypeSignature typeSignature;

    /**
     * Creates a new instance.
     */
    ParamInfo(String name, FieldLocation location, FieldRequirement requirement,
              TypeSignature typeSignature) {
        this.name = requireNonNull(name, "name");
        this.location = requireNonNull(location, "location");
        this.requirement = requireNonNull(requirement, "requirement");
        this.typeSignature = requireNonNull(typeSignature, "typeSignature");
    }

    /**
     * Returns the name of the parameter.
     */
    @JsonProperty
    public String name() {
        return name;
    }

    /**
     * Returns the location of the parameter.
     */
    @JsonProperty
    public FieldLocation location() {
        return location;
    }

    /**
     * Returns the requirement level of the parameter.
     */
    @JsonProperty
    public FieldRequirement requirement() {
        return requirement;
    }

    /**
     * Returns the metadata about the type of the parameter.
     */
    @JsonProperty
    public TypeSignature typeSignature() {
        return typeSignature;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof ParamInfo)) {
            return false;
        }

        final ParamInfo that = (ParamInfo) o;
        return name.equals(that.name) &&
               location == that.location &&
               requirement == that.requirement &&
               typeSignature.equals(that.typeSignature);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, location, requirement, typeSignature);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).omitNullValues()
                          .add("name", name)
                          .add("location", location)
                          .add("requirement", requirement)
                          .add("typeSignature", typeSignature)
                          .toString();
    }
}
