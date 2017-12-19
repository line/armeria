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

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Strings;

/**
 * Metadata about an enum value.
 */
public final class EnumValueInfo {

    private final String name;
    private final String docString;

    /**
     * Creates a new instance.
     *
     * @param name the name of the enum value
     */
    public EnumValueInfo(String name) {
        this(name, null);
    }

    /**
     * Creates a new instance.
     *
     * @param name the name of the enum value
     * @param docString the documentation string that describes the enum value
     */
    public EnumValueInfo(String name, @Nullable String docString) {
        this.name = requireNonNull(name, "name");
        this.docString = Strings.emptyToNull(docString);
    }

    /**
     * Returns the name of the enum value.
     */
    @JsonProperty
    public String name() {
        return name;
    }

    /**
     * Returns the documentation string that describes the enum value.
     */
    @JsonProperty
    @JsonInclude(Include.NON_NULL)
    @Nullable
    public String docString() {
        return docString;
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        return name.equals(((EnumValueInfo) o).name);
    }

    @Override
    public String toString() {
        return name();
    }
}
