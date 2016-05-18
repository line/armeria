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

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

import org.apache.thrift.meta_data.MapMetaData;
import org.apache.thrift.protocol.TType;

import com.fasterxml.jackson.annotation.JsonProperty;

class MapInfo extends TypeInfo {

    static MapInfo of(MapMetaData mapMetaData) {
        return of(mapMetaData, Collections.emptyMap());
    }

    static MapInfo of(MapMetaData mapMetaData, Map<String, String> docStrings) {
        requireNonNull(mapMetaData, "mapMetaData");

        assert mapMetaData.type == TType.MAP;
        assert !mapMetaData.isBinary();

        return new MapInfo(TypeInfo.of(mapMetaData.keyMetaData, docStrings),
                           TypeInfo.of(mapMetaData.valueMetaData, docStrings));
    }

    static MapInfo of(TypeInfo keyType, TypeInfo valueType) {
        return new MapInfo(keyType, valueType);
    }

    private final TypeInfo keyType;
    private final TypeInfo valueType;

    private MapInfo(TypeInfo keyType, TypeInfo valueType) {
        super(ValueType.MAP, false);

        this.keyType = requireNonNull(keyType, "keyType");
        this.valueType = requireNonNull(valueType, "valueType");
    }

    @JsonProperty
    public TypeInfo keyType() {
        return keyType;
    }

    @JsonProperty
    public TypeInfo valueType() {
        return valueType;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) { return true; }
        if (o == null || getClass() != o.getClass()) { return false; }
        if (!super.equals(o)) { return false; }
        MapInfo mapInfo = (MapInfo) o;
        return Objects.equals(keyType, mapInfo.keyType) &&
               Objects.equals(valueType, mapInfo.valueType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), keyType, valueType);
    }

    @Override
    public String toString() {
        return "MAP<" + keyType + ", " + valueType + '>';
    }
}
