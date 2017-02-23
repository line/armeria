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

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Metadata about a struct type, an enum type or an exception type.
 */
public interface ClassInfo extends TypeInfo {

    /**
     * Returns the fully qualified type name.
     */
    @JsonProperty
    String name();

    /**
     * Returns the simple type name, which does not include the package name.
     */
    @JsonProperty
    default String simpleName() {
        final int dotIdx = name().lastIndexOf('.');
        if (dotIdx < 0) {
            return name();
        } else {
            return name().substring(dotIdx + 1);
        }
    }

    /**
     * Returns the package name.
     */
    @JsonProperty
    default String packageName() {
        final int dotIdx = name().lastIndexOf('.');
        if (dotIdx < 0) {
            return "";
        } else {
            return name().substring(0, dotIdx);
        }
    }

    @Override
    default String signature() {
        return name();
    }

    /**
     * Returns the metadata about the fields of the type.
     */
    @JsonProperty
    List<FieldInfo> fields();

    /**
     * Returns the constants defined by the type.
     */
    @JsonProperty
    List<Object> constants();

    /**
     * Returns the documentation string.
     */
    @JsonProperty
    String docString();
}
