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

import java.util.Map;
import java.util.Objects;

import org.apache.thrift.meta_data.EnumMetaData;
import org.apache.thrift.meta_data.FieldValueMetaData;
import org.apache.thrift.meta_data.ListMetaData;
import org.apache.thrift.meta_data.MapMetaData;
import org.apache.thrift.meta_data.SetMetaData;
import org.apache.thrift.meta_data.StructMetaData;

import com.fasterxml.jackson.annotation.JsonProperty;

class TypeInfo {
    static final TypeInfo VOID = new TypeInfo(ValueType.VOID, false);

    static TypeInfo of(FieldValueMetaData fieldValueMetaData, Map<String, String> docStrings) {
        if (fieldValueMetaData instanceof StructMetaData) {
            return StructInfo.of((StructMetaData) fieldValueMetaData, docStrings);
        }

        if (fieldValueMetaData instanceof EnumMetaData) {
            return EnumInfo.of((EnumMetaData) fieldValueMetaData, docStrings);
        }

        if (fieldValueMetaData instanceof ListMetaData) {
            return ListInfo.of((ListMetaData) fieldValueMetaData, docStrings);
        }

        if (fieldValueMetaData instanceof SetMetaData) {
            return SetInfo.of((SetMetaData) fieldValueMetaData, docStrings);
        }

        if (fieldValueMetaData instanceof MapMetaData) {
            return MapInfo.of((MapMetaData) fieldValueMetaData, docStrings);
        }

        return new TypeInfo(ValueType.of(fieldValueMetaData.type), fieldValueMetaData.isBinary());
    }

    static TypeInfo of(ValueType type, boolean binary) {
        return new TypeInfo(type, binary);
    }

    private final ValueType type;
    private final boolean binary;

    protected TypeInfo(ValueType type, boolean binary) {
        this.type = requireNonNull(type, "type");
        this.binary = requireNonNull(binary, "binary");
    }

    public ValueType type() {
        return type;
    }

    @JsonProperty
    public boolean isBinary() {
        return binary;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) { return true; }
        if (o == null || getClass() != o.getClass()) { return false; }
        TypeInfo typeInfo = (TypeInfo) o;
        return Objects.equals(binary, typeInfo.binary) &&
               Objects.equals(type, typeInfo.type);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, binary);
    }

    @JsonProperty("type")
    @Override
    public String toString() {
        return binary ? "BINARY" : type.name();
    }
}
