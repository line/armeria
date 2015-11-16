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

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.apache.thrift.meta_data.FieldMetaData;
import org.apache.thrift.meta_data.StructMetaData;
import org.apache.thrift.protocol.TType;

class StructInfo extends TypeInfo implements ClassInfo {

    static StructInfo of(StructMetaData structMetaData) {
        final Class<?> structClass = structMetaData.structClass;

        assert structMetaData.type == TType.STRUCT;
        assert !structMetaData.isBinary();

        final Map<?, FieldMetaData> metaDataMap =
                FieldMetaData.getStructMetaDataMap(structMetaData.structClass);
        final List<FieldInfo> fields = metaDataMap.values().stream()
                                                  .map(FieldInfo::of).collect(Collectors.toList());

        return new StructInfo(structClass.getName(), fields);
    }

    static StructInfo of(String name, List<FieldInfo> fields) {
        return new StructInfo(name, fields);
    }

    private final String name;
    private final List<FieldInfo> fields;

    private StructInfo(String name, List<FieldInfo> fields) {
        super(ValueType.STRUCT, false);

        this.name = requireNonNull(name, "name");
        this.fields = Collections.unmodifiableList(requireNonNull(fields, "fields"));
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public List<FieldInfo> fields() {
        return fields;
    }

    @Override
    public List<Object> constants() {
        return Collections.emptyList();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) { return true; }
        if (o == null || getClass() != o.getClass()) { return false; }
        if (!super.equals(o)) { return false; }
        StructInfo that = (StructInfo) o;
        return Objects.equals(name, that.name) &&
               Objects.equals(fields, that.fields);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), name, fields);
    }

    @Override
    public String toString() {
        return name;
    }
}
