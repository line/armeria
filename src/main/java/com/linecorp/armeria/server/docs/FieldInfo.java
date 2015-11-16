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

import static java.util.Objects.requireNonNull;

import java.util.Objects;

import org.apache.thrift.meta_data.FieldMetaData;

import com.fasterxml.jackson.annotation.JsonProperty;

class FieldInfo {

    static FieldInfo of(FieldMetaData fieldMetaData) {
        requireNonNull(fieldMetaData, "fieldMetaData");

        return new FieldInfo(fieldMetaData.fieldName, RequirementType.of(fieldMetaData.requirementType),
                             TypeInfo.of(fieldMetaData.valueMetaData));
    }

    static FieldInfo of(String name, RequirementType requirementType, TypeInfo type) {
        return new FieldInfo(name, requirementType, type);
    }

    private final String name;
    private final RequirementType requirementType;
    private final TypeInfo type;

    private FieldInfo(String name, RequirementType requirementType, TypeInfo type) {
        this.name = requireNonNull(name, "name");
        this.requirementType = requireNonNull(requirementType, "requirementType");
        this.type = requireNonNull(type, "type");
    }

    @JsonProperty
    public String name() {
        return name;
    }

    @JsonProperty
    public RequirementType requirementType() {
        return requirementType;
    }

    @JsonProperty
    public TypeInfo type() {
        return type;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) { return true; }
        if (o == null || getClass() != o.getClass()) { return false; }
        FieldInfo fieldInfo = (FieldInfo) o;
        return Objects.equals(name, fieldInfo.name) &&
               Objects.equals(requirementType, fieldInfo.requirementType) &&
               Objects.equals(type, fieldInfo.type);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, requirementType, type);
    }

    @Override
    public String toString() {
        return "FieldInfo{" +
               "name='" + name + '\'' +
               ", requirementType=" + requirementType +
               ", type=" + type +
               '}';
    }
}
