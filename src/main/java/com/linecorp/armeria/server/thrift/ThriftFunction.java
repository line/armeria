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

package com.linecorp.armeria.server.thrift;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import org.apache.thrift.AsyncProcessFunction;
import org.apache.thrift.ProcessFunction;
import org.apache.thrift.TBase;
import org.apache.thrift.TFieldIdEnum;
import org.apache.thrift.meta_data.FieldMetaData;
import org.apache.thrift.meta_data.FieldValueMetaData;

import com.linecorp.armeria.common.thrift.ThriftUtil;

final class ThriftFunction {

    private enum Type {
        SYNC,
        ASYNC
    }

    private final String name;
    private final Object func;
    private final Type type;
    private final TBase<TBase<?, ?>, TFieldIdEnum> result;
    private final TFieldIdEnum successField;
    private final Map<Class<Throwable>, TFieldIdEnum> exceptionFields = new HashMap<>();
    private final List<Class<?>> paramTypes;
    private final Class<?> returnType;

    ThriftFunction(ProcessFunction<?, ?> func) throws Exception {
        this(func.getMethodName(), func, Type.SYNC, getResultType(func));
    }

    ThriftFunction(AsyncProcessFunction<?, ?, ?> func) throws Exception {
        this(func.getMethodName(), func, Type.ASYNC, getResultType(func));
    }

    private ThriftFunction(
            String name, Object func, Type type,
            Class<TBase<TBase<?, ?>, TFieldIdEnum>> resultType) throws Exception {

        this.name = name;
        this.func = func;
        this.type = type;

        // Determine the parameter types of the function.
        paramTypes = Collections.unmodifiableList(
                FieldMetaData.getStructMetaDataMap(newArgs().getClass()).values().stream()
                        .map(e -> ThriftUtil.toJavaType(e.valueMetaData)).collect(Collectors.toList()));

        // Determine the success and exception fields of the function.
        TFieldIdEnum successField = null;
        FieldValueMetaData successFieldMetadata = null;

        if (resultType != null) {
            result = resultType.newInstance();

            @SuppressWarnings("unchecked")
            final Map<TFieldIdEnum, FieldMetaData> metaDataMap =
                    (Map<TFieldIdEnum, FieldMetaData>) FieldMetaData.getStructMetaDataMap(resultType);

            for (Entry<TFieldIdEnum, FieldMetaData> e: metaDataMap.entrySet()) {
                final TFieldIdEnum key = e.getKey();
                final String fieldName = key.getFieldName();
                if ("success".equals(fieldName)) {
                    successField = key;
                    successFieldMetadata = e.getValue().valueMetaData;
                    continue;
                }

                Class<?> fieldType = resultType.getField(fieldName).getType();
                if (Throwable.class.isAssignableFrom(fieldType)) {
                    @SuppressWarnings("unchecked")
                    Class<Throwable> exceptionFieldType = (Class<Throwable>) fieldType;
                    exceptionFields.put(exceptionFieldType, key);
                }
            }
        } else {
            result = null;
        }

        this.successField = successField;

        if (successFieldMetadata != null) {
            returnType = ThriftUtil.toJavaType(successFieldMetadata);
        } else {
            returnType = Void.class;
        }
    }

    boolean isOneway() {
        return result == null;
    }

    boolean isAsync() {
        return type == Type.ASYNC;
    }

    @SuppressWarnings("unchecked")
    ProcessFunction<Object, TBase<TBase<?, ?>, TFieldIdEnum>> syncFunc() {
        return (ProcessFunction<Object, TBase<TBase<?, ?>, TFieldIdEnum>>) func;
    }

    @SuppressWarnings("unchecked")
    AsyncProcessFunction<Object, TBase<TBase<?, ?>, TFieldIdEnum>, Object> asyncFunc() {
        return (AsyncProcessFunction<Object, TBase<TBase<?, ?>, TFieldIdEnum>, Object>) func;
    }

    String methodName() {
        if (isAsync()) {
            return asyncFunc().getMethodName();
        } else {
            return syncFunc().getMethodName();
        }
    }

    List<Class<?>> paramTypes() {
        return paramTypes;
    }

    Class<?> returnType() {
        return returnType;
    }

    boolean hasSuccess() {
        return successField != null;
    }

    TBase<TBase<?, ?>, TFieldIdEnum> newArgs() {
        if (isAsync()) {
            return asyncFunc().getEmptyArgsInstance();
        } else {
            return syncFunc().getEmptyArgsInstance();
        }
    }

    boolean isResult(Object obj) {
        return result != null && result.getClass().isInstance(obj);
    }

    TBase<TBase<?, ?>, TFieldIdEnum> newResult() {
        return result.deepCopy();
    }

    void setSuccess(TBase<?, TFieldIdEnum> result, Object value) {
        if (successField != null) {
            result.setFieldValue(successField, value);
        }
    }

    boolean setException(TBase<?, TFieldIdEnum> result, Throwable cause) {
        Class<?> causeType = cause.getClass();
        for (Entry<Class<Throwable>, TFieldIdEnum> e: exceptionFields.entrySet()) {
            if (e.getKey().isAssignableFrom(causeType)) {
                result.setFieldValue(e.getValue(), cause);
                return true;
            }
        }
        return false;
    }

    private static Class<TBase<TBase<?, ?>, TFieldIdEnum>> getResultType(ProcessFunction<?, ?> func) {
        return getResultType0(Type.SYNC, func.getClass(), func.getMethodName());
    }

    private static Class<TBase<TBase<?, ?>, TFieldIdEnum>> getResultType(AsyncProcessFunction<?, ?, ?> asyncFunc) {
        return getResultType0(Type.ASYNC, asyncFunc.getClass(), asyncFunc.getMethodName());
    }

    private static Class<TBase<TBase<?, ?>, TFieldIdEnum>> getResultType0(
            Type type, Class<?> funcClass, String methodName) {

        final String funcClassName = funcClass.getName();
        final int serviceClassEndPos = funcClassName.lastIndexOf(
                (type == Type.SYNC? "$Processor$" : "$AsyncProcessor$") + methodName);

        if (serviceClassEndPos <= 0) {
            throw new IllegalStateException("cannot determine the result class of method: " + methodName);
        }

        try {
            @SuppressWarnings("unchecked")
            Class<TBase<TBase<?, ?>, TFieldIdEnum>> resultType =
                    (Class<TBase<TBase<?, ?>, TFieldIdEnum>>) Class.forName(
                            funcClassName.substring(0, serviceClassEndPos) + '$' + methodName + "_result",
                            false, funcClass.getClassLoader());
            return resultType;
        } catch (ClassNotFoundException ignored) {
            // Oneway function does not have a result type.
            return null;
        }
    }
}
