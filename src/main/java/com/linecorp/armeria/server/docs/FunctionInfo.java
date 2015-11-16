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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.apache.thrift.TBase;
import org.apache.thrift.TException;
import org.apache.thrift.TFieldIdEnum;
import org.apache.thrift.meta_data.FieldMetaData;

import com.fasterxml.jackson.annotation.JsonProperty;

class FunctionInfo {

    static FunctionInfo of(Method method) throws ClassNotFoundException {
        requireNonNull(method, "method");

        final String methodName = method.getName();

        final Class<?> serviceClass = method.getDeclaringClass().getDeclaringClass();
        final String serviceName = serviceClass.getName();
        final ClassLoader classLoader = serviceClass.getClassLoader();

        @SuppressWarnings("unchecked")
        final FunctionInfo function =
                new FunctionInfo(methodName,
                                 (Class<? extends TBase>) Class.forName(
                                         serviceName + '$' + methodName + "_args", false, classLoader),
                                 (Class<? extends TBase>) Class.forName(
                                         serviceName + '$' + methodName + "_result", false, classLoader),
                                 (Class<? extends TException>[]) method.getExceptionTypes());
        return function;
    }

    private final String name;
    private final TypeInfo returnType;
    private final List<FieldInfo> parameters;
    private final List<ExceptionInfo> exceptions;

    private FunctionInfo(String name,
                         Class<? extends TBase> argsClass,
                         Class<? extends TBase> resultClass,
                         Class<? extends TException>[] exceptionClasses) {
        this.name = requireNonNull(name, "name");
        requireNonNull(argsClass, "argsClass");
        requireNonNull(resultClass, "resultClass");
        requireNonNull(exceptionClasses, "exceptionClasses");

        final Map<? extends TFieldIdEnum, FieldMetaData> argsMetaData =
                FieldMetaData.getStructMetaDataMap(argsClass);
        parameters = Collections.unmodifiableList(
                argsMetaData.values().stream().map(FieldInfo::of).collect(Collectors.toList()));

        final Map<? extends TFieldIdEnum, FieldMetaData> resultMetaData =
                FieldMetaData.getStructMetaDataMap(resultClass);
        FieldInfo fieldInfo = null;
        for (FieldMetaData fieldMetaData : resultMetaData.values()) {
            if ("success".equals(fieldMetaData.fieldName)) {
                fieldInfo = FieldInfo.of(fieldMetaData);
                break;
            }
        }
        if (fieldInfo == null) {
            returnType = TypeInfo.VOID;
        } else {
            returnType = fieldInfo.type();
        }

        final List<ExceptionInfo> exceptions0 = new ArrayList<>(exceptionClasses.length);
        for (Class<? extends TException> exceptionClass : exceptionClasses) {
            if (exceptionClass == TException.class) {
                continue;
            }
            exceptions0.add(ExceptionInfo.of(exceptionClass));
        }
        exceptions = Collections.unmodifiableList(exceptions0);
    }

    @JsonProperty
    public String name() {
        return name;
    }

    @JsonProperty
    public TypeInfo returnType() {
        return returnType;
    }

    @JsonProperty
    public List<FieldInfo> parameters() {
        return parameters;
    }

    @JsonProperty
    public List<ExceptionInfo> exceptions() {
        return exceptions;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) { return true; }
        if (o == null || getClass() != o.getClass()) { return false; }
        FunctionInfo that = (FunctionInfo) o;
        return Objects.equals(name, that.name) &&
               Objects.equals(returnType, that.returnType) &&
               Objects.equals(parameters, that.parameters) &&
               Objects.equals(exceptions, that.exceptions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, returnType, parameters, exceptions);
    }

    @Override
    public String toString() {
        return "FunctionInfo{" +
               "name='" + name + '\'' +
               ", returnType=" + returnType +
               ", parameters=" + parameters +
               ", exceptions=" + exceptions +
               '}';
    }
}
