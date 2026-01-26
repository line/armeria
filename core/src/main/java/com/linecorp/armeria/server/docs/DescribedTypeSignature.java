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
 * A {@link TypeSignature} with an associated {@link DescriptionInfo}.
 * This is used to represent a method's return type or thrown exceptions with their documentation.
 */
@UnstableApi
public final class DescribedTypeSignature {

    /**
     * Creates a new instance with the specified {@link TypeSignature} and an empty description.
     */
    public static DescribedTypeSignature of(TypeSignature typeSignature) {
        return new DescribedTypeSignature(typeSignature, DescriptionInfo.empty());
    }

    /**
     * Creates a new instance with the specified {@link TypeSignature} and {@link DescriptionInfo}.
     */
    public static DescribedTypeSignature of(TypeSignature typeSignature, DescriptionInfo descriptionInfo) {
        return new DescribedTypeSignature(typeSignature, descriptionInfo);
    }

    private final TypeSignature typeSignature;
    private final DescriptionInfo descriptionInfo;

    private DescribedTypeSignature(TypeSignature typeSignature, DescriptionInfo descriptionInfo) {
        this.typeSignature = requireNonNull(typeSignature, "typeSignature");
        this.descriptionInfo = requireNonNull(descriptionInfo, "descriptionInfo");
    }

    /**
     * Returns the {@link TypeSignature}.
     */
    @JsonProperty
    public TypeSignature typeSignature() {
        return typeSignature;
    }

    /**
     * Returns the {@link DescriptionInfo}.
     */
    @JsonProperty
    public DescriptionInfo descriptionInfo() {
        return descriptionInfo;
    }

    /**
     * Returns a new {@link DescribedTypeSignature} with the specified {@link DescriptionInfo}.
     * Returns {@code this} if this {@link DescribedTypeSignature} has the same {@link DescriptionInfo}.
     */
    public DescribedTypeSignature withDescriptionInfo(DescriptionInfo descriptionInfo) {
        requireNonNull(descriptionInfo, "descriptionInfo");
        if (descriptionInfo.equals(this.descriptionInfo)) {
            return this;
        }
        return new DescribedTypeSignature(typeSignature, descriptionInfo);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DescribedTypeSignature)) {
            return false;
        }
        final DescribedTypeSignature that = (DescribedTypeSignature) o;
        return typeSignature.equals(that.typeSignature) &&
               descriptionInfo.equals(that.descriptionInfo);
    }

    @Override
    public int hashCode() {
        return Objects.hash(typeSignature, descriptionInfo);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("typeSignature", typeSignature)
                          .add("descriptionInfo", descriptionInfo)
                          .toString();
    }
}
