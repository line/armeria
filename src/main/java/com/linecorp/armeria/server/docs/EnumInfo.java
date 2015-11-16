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

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.apache.thrift.meta_data.EnumMetaData;
import org.apache.thrift.protocol.TType;

class EnumInfo extends TypeInfo implements ClassInfo {

    static EnumInfo of(EnumMetaData enumMetaData) {
        requireNonNull(enumMetaData, "enumMetaData");

        final Class<?> enumClass = enumMetaData.enumClass;

        assert enumMetaData.type == TType.ENUM;
        assert !enumMetaData.isBinary();

        final List<Object> constants = new ArrayList<>();
        final Field[] fields = enumClass.getDeclaredFields();
        for (Field field : fields) {
            if (field.isEnumConstant()) {
                try {
                    constants.add(field.get(null));
                } catch (IllegalAccessException ignored) {}
            }
        }

        return new EnumInfo(enumClass.getName(), constants);
    }

    static EnumInfo of(String name, List<Object> constants) {
        return new EnumInfo(name, constants);
    }

    private final String name;
    private final List<Object> constants;

    private EnumInfo(String name, List<Object> constants) {
        super(ValueType.ENUM, false);

        this.name = requireNonNull(name, "name");
        this.constants = Collections.unmodifiableList(requireNonNull(constants, "constants"));
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public List<FieldInfo> fields() {
        return Collections.emptyList();
    }

    @Override
    public List<Object> constants() {
        return constants;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) { return true; }
        if (o == null || getClass() != o.getClass()) { return false; }
        if (!super.equals(o)) { return false; }
        EnumInfo enumInfo = (EnumInfo) o;
        return Objects.equals(name, enumInfo.name) &&
               Objects.equals(constants, enumInfo.constants);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), name, constants);
    }

    @Override
    public String toString() {
        return name;
    }
}
