/*
 *  Copyright 2019 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */
package com.linecorp.armeria.server.docs;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.List;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Creates a new {@link FieldInfo} using the builder pattern.
 */
@UnstableApi
public final class FieldInfoBuilder {

    private final String name;
    private final TypeSignature typeSignature;
    private final List<FieldInfo> childFieldInfos;

    private FieldRequirement requirement = FieldRequirement.UNSPECIFIED;
    private FieldLocation location = FieldLocation.UNSPECIFIED;
    @Nullable
    private String docString;

    FieldInfoBuilder(String name, TypeSignature typeSignature) {
        this.name = requireNonNull(name, "name");
        this.typeSignature = requireNonNull(typeSignature, "typeSignature");
        childFieldInfos = ImmutableList.of();
    }

    FieldInfoBuilder(String name, TypeSignature typeSignature, FieldInfo... childFieldInfos) {
        this(name, typeSignature, ImmutableList.copyOf(childFieldInfos));
    }

    FieldInfoBuilder(String name, TypeSignature typeSignature, Iterable<FieldInfo> childFieldInfos) {
        this.name = requireNonNull(name, "name");
        this.typeSignature = typeSignature;
        checkArgument(!Iterables.isEmpty(requireNonNull(childFieldInfos, "childFieldInfos")),
                      "childFieldInfos can't be empty.");
        this.childFieldInfos = ImmutableList.copyOf(childFieldInfos);
    }

    /**
     * Sets the {@link FieldRequirement} of the field.
     */
    public FieldInfoBuilder requirement(FieldRequirement requirement) {
        this.requirement = requireNonNull(requirement, "requirement");
        return this;
    }

    /**
     * Sets the {@link FieldLocation} of the field.
     */
    public FieldInfoBuilder location(FieldLocation location) {
        this.location = requireNonNull(location, "location");
        return this;
    }

    /**
     * Sets the documentation string of the field.
     */
    public FieldInfoBuilder docString(String docString) {
        this.docString = requireNonNull(docString, "docString");
        return this;
    }

    /**
     * Returns a newly-created {@link FieldInfo} based on the properties of this builder.
     */
    public FieldInfo build() {
        return new FieldInfo(name, location, requirement, typeSignature, childFieldInfos, docString);
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
