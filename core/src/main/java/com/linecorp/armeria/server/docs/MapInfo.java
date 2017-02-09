/*
 * Copyright 2016 LINE Corporation
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

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Metadata about a map-type value.
 */
public final class MapInfo implements TypeInfo {

    private final TypeInfo keyTypeInfo;
    private final TypeInfo valueTypeInfo;

    /**
     * Creates a new instance.
     */
    public MapInfo(TypeInfo keyTypeInfo, TypeInfo valueTypeInfo) {
        this.keyTypeInfo = requireNonNull(keyTypeInfo, "keyTypeInfo");
        this.valueTypeInfo = requireNonNull(valueTypeInfo, "valueTypeInfo");
    }

    @Override
    public Type type() {
        return Type.MAP;
    }

    @Override
    public String signature() {
        return "MAP<" + keyTypeInfo.signature() + ", " + valueTypeInfo.signature() + '>';
    }

    /**
     * Returns the metadata about the key type of the map.
     */
    @JsonProperty
    public TypeInfo keyTypeInfo() {
        return keyTypeInfo;
    }

    /**
     * Returns the metadata about the value type of the map.
     */
    @JsonProperty
    public TypeInfo valueTypeInfo() {
        return valueTypeInfo;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        final MapInfo that = (MapInfo) o;
        return keyTypeInfo.equals(that.keyTypeInfo) &&
               valueTypeInfo.equals(that.valueTypeInfo);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type(), keyTypeInfo, valueTypeInfo);
    }

    @Override
    public String toString() {
        return signature();
    }
}
