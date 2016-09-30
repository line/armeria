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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import org.apache.thrift.TBase;
import org.apache.thrift.TException;
import org.apache.thrift.TFieldIdEnum;
import org.apache.thrift.TSerializer;
import org.apache.thrift.meta_data.FieldMetaData;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.linecorp.armeria.common.thrift.ThriftProtocolFactories;

final class FunctionInfo {

    static FunctionInfo of(Method method, Map<Class<?>, ? extends TBase<?, ?>> sampleRequests)
            throws ClassNotFoundException {
        return of(method, sampleRequests, null, Collections.emptyMap());
    }

    static FunctionInfo of(Method method,
                           Map<Class<?>, ? extends TBase<?, ?>> sampleRequests,
                           @Nullable String namespace,
                           Map<String, String> docStrings) throws ClassNotFoundException {
        requireNonNull(method, "method");

        final String methodName = method.getName();

        final Class<?> serviceClass = method.getDeclaringClass().getDeclaringClass();
        final String serviceName = serviceClass.getName();
        final ClassLoader classLoader = serviceClass.getClassLoader();

        @SuppressWarnings("unchecked")
        Class<? extends TBase<?, ?>> argsClass = (Class<? extends TBase<?, ?>>) Class.forName(
                serviceName + '$' + methodName + "_args", false, classLoader);
        String sampleJsonRequest;
        TBase<?, ?> sampleRequest = sampleRequests.get(argsClass);
        if (sampleRequest == null) {
            sampleJsonRequest = "";
        } else {
            TSerializer serializer = new TSerializer(ThriftProtocolFactories.TEXT);
            try {
                sampleJsonRequest = serializer.toString(sampleRequest, StandardCharsets.UTF_8.name());
            } catch (TException e) {
                throw new IllegalArgumentException(
                        "Failed to serialize to a memory buffer, this shouldn't ever happen.", e);
            }
        }

        Class<?> resultClass;
        try {
            resultClass =  Class.forName(serviceName + '$' + methodName + "_result", false, classLoader);
        } catch (ClassNotFoundException ignored) {
            // Oneway function does not have a result type.
            resultClass = null;
        }

        @SuppressWarnings("unchecked")
        final FunctionInfo function =
                new FunctionInfo(namespace,
                                 methodName,
                                 argsClass,
                                 (Class<? extends TBase<?, ?>>) resultClass,
                                 (Class<? extends TException>[]) method.getExceptionTypes(),
                                 sampleJsonRequest,
                                 docStrings);
        return function;
    }

    private final String name;
    private final TypeInfo returnType;
    private final List<FieldInfo> parameters;
    private final List<ExceptionInfo> exceptions;
    private final String sampleJsonRequest;
    private final String docString;

    private FunctionInfo(String namespace,
                         String name,
                         Class<? extends TBase<?, ?>> argsClass,
                         @Nullable Class<? extends TBase<?, ?>> resultClass,
                         Class<? extends TException>[] exceptionClasses,
                         String sampleJsonRequest,
                         Map<String, String> docStrings) {
        this.name = requireNonNull(name, "name");
        this.sampleJsonRequest = requireNonNull(sampleJsonRequest, "sampleJsonRequest");
        final String functionNamespace = ThriftDocString.key(namespace, name);
        this.docString = docStrings.get(functionNamespace);
        requireNonNull(argsClass, "argsClass");
        requireNonNull(exceptionClasses, "exceptionClasses");

        final Map<? extends TFieldIdEnum, FieldMetaData> argsMetaData =
                FieldMetaData.getStructMetaDataMap(argsClass);
        parameters = Collections.unmodifiableList(
                argsMetaData.values().stream()
                            .map(fieldMetaData -> FieldInfo.of(fieldMetaData, functionNamespace, docStrings))
                            .collect(Collectors.toList()));

        FieldInfo fieldInfo = null;
        if (resultClass != null) { // Function isn't "oneway" function
            final Map<? extends TFieldIdEnum, FieldMetaData> resultMetaData =
                    FieldMetaData.getStructMetaDataMap(resultClass);

            for (FieldMetaData fieldMetaData : resultMetaData.values()) {
                if ("success".equals(fieldMetaData.fieldName)) {
                    fieldInfo = FieldInfo.of(fieldMetaData, functionNamespace, docStrings);
                    break;
                }
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
            exceptions0.add(ExceptionInfo.of(exceptionClass, docStrings));
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

    @JsonProperty
    public String sampleJsonRequest() {
        return sampleJsonRequest;
    }

    @JsonProperty
    public String docString() {
        return docString;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (o == null || getClass() != o.getClass()) {
            return false;
        }

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
