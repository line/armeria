/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.server.docs;

import static java.util.Objects.requireNonNull;

import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Metadata about a discriminator object, which is used for polymorphism.
 * This corresponds to the {@code discriminator} object in the OpenAPI Specification.
 * @see <a href="https://swagger.io/docs/specification/data-models/inheritance-and-polymorphism/">Inheritance and Polymorphism</a>
 */
@UnstableApi
public final class DiscriminatorInfo {

    private final String propertyName;
    private final Map<String, String> mapping;

    /**
     * Creates a new {@link DiscriminatorInfo} with {@code propertyName}, the name of the property
     * int the payload that will be used to differentiate between schemas.
     * and {@code mapping} a map of payload values to schema names or references.
     */
    public static DiscriminatorInfo of(String propertyName, Map<String, String> mapping) {
        return new DiscriminatorInfo(propertyName, mapping);
    }

    /**
     * Creates a new instance.
     */
    DiscriminatorInfo(String propertyName, Map<String, String> mapping) {
        this.propertyName = requireNonNull(propertyName, "propertyName");
        this.mapping = ImmutableMap.copyOf(requireNonNull(mapping, "mapping"));
    }

    /**
     * Returns the name of the property that is used to differentiate between schemas.
     */
    @JsonProperty
    public String propertyName() {
        return propertyName;
    }

    /**
     * Returns the map of payload values to schema names.
     * The keys are the values that appear in the {@link #propertyName()} field, and the values are
     * the schema definitions to use for that value (e.g., {@code "#/definitions/Cat"}).
     */
    @JsonProperty
    public Map<String, String> mapping() {
        return mapping;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DiscriminatorInfo)) {
            return false;
        }
        final DiscriminatorInfo that = (DiscriminatorInfo) o;
        return propertyName.equals(that.propertyName) && mapping.equals(that.mapping);
    }

    @Override
    public int hashCode() {
        return Objects.hash(propertyName, mapping);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).add("propertyName", propertyName).add("mapping", mapping)
                          .toString();
    }
}
