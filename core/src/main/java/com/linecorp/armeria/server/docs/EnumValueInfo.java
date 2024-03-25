/*
 *  Copyright 2017 LINE Corporation
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

import static java.util.Objects.requireNonNull;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Metadata about an enum value.
 */
@UnstableApi
public final class EnumValueInfo {

    private final String name;
    private final DescriptionInfo descriptionInfo;
    @Nullable
    private final Integer intValue;

    /**
     * Creates a new instance.
     *
     * @param name the name of the enum value
     */
    public EnumValueInfo(String name) {
        this(name, null, DescriptionInfo.empty());
    }

    /**
     * Creates a new instance.
     *
     * @param name the name of the enum value
     * @param intValue the integer value of the enum value
     */
    public EnumValueInfo(String name, @Nullable Integer intValue) {
        this(name, intValue, DescriptionInfo.empty());
    }

    /**
     * Creates a new instance.
     *
     * @param name the name of the enum value
     * @param intValue the integer value of the enum value
     * @param descriptionInfo the description object that describes the enum value
     */
    public EnumValueInfo(String name, @Nullable Integer intValue,
                         DescriptionInfo descriptionInfo) {
        this.name = requireNonNull(name, "name");
        this.intValue = intValue;
        this.descriptionInfo = descriptionInfo;
    }

    /**
     * Returns the name of the enum value.
     */
    @JsonProperty
    public String name() {
        return name;
    }

    /**
     * Returns the integer value of the enum value.
     */
    @JsonProperty
    @JsonInclude(Include.NON_NULL)
    @Nullable
    public Integer intValue() {
        return intValue;
    }

    /**
     * Returns the description information that describes the enum value.
     */
    @JsonProperty
    public DescriptionInfo descriptionInfo() {
        return descriptionInfo;
    }

    /**
     * Returns a new {@link EnumValueInfo} with the specified {@link DescriptionInfo}.
     * Returns {@code this} if this {@link EnumValueInfo} has the same {@link DescriptionInfo}.
     */
    public EnumValueInfo withDescriptionInfo(DescriptionInfo descriptionInfo) {
        requireNonNull(descriptionInfo, "descriptionInfo");
        if (descriptionInfo.equals(this.descriptionInfo)) {
            return this;
        }

        return new EnumValueInfo(name, intValue, descriptionInfo);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, intValue, descriptionInfo);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EnumValueInfo)) {
            return false;
        }

        final EnumValueInfo that = (EnumValueInfo) o;
        return name.equals(that.name) &&
               Objects.equals(intValue, that.intValue) &&
               descriptionInfo.equals(that.descriptionInfo);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .omitNullValues()
                          .add("name", name)
                          .add("intValue", intValue)
                          .add("descriptionInfo", descriptionInfo)
                          .toString();
    }
}
