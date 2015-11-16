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

import org.apache.thrift.TException;
import org.apache.thrift.meta_data.FieldMetaData;

class ExceptionInfo extends TypeInfo implements ClassInfo {

    static ExceptionInfo of(Class<? extends TException> exceptionClass) {
        requireNonNull(exceptionClass, "exceptionClass");

        List<FieldInfo> fields;
        try {
            @SuppressWarnings("unchecked")
            final Map<?, FieldMetaData> metaDataMap =
                    (Map<?, FieldMetaData>) exceptionClass.getDeclaredField("metaDataMap").get(null);

            fields = metaDataMap.values().stream().map(FieldInfo::of).collect(Collectors.toList());
        } catch (IllegalAccessException e) {
            throw new AssertionError("will not happen", e);
        } catch (NoSuchFieldException ignored) {
            fields = Collections.emptyList();
        }

        return new ExceptionInfo(exceptionClass.getName(), fields);
    }

    static ExceptionInfo of(String name, List<FieldInfo> fields) {
        return new ExceptionInfo(name, fields);
    }

    private final String name;
    private final List<FieldInfo> fields;

    private ExceptionInfo(String name, List<FieldInfo> fields) {
        super(ValueType.STRUCT, false);

        this.name = requireNonNull(name, "name");
        this.fields = requireNonNull(Collections.unmodifiableList(fields), "fields");
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
        ExceptionInfo that = (ExceptionInfo) o;
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
