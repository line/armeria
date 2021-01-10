/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.internal.common.thrift;

import static java.util.Objects.requireNonNull;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.Nullable;

import org.apache.thrift.AsyncProcessFunction;
import org.apache.thrift.ProcessFunction;
import org.apache.thrift.TApplicationException;
import org.apache.thrift.TBase;
import org.apache.thrift.TException;
import org.apache.thrift.TFieldIdEnum;
import org.apache.thrift.meta_data.FieldMetaData;
import org.apache.thrift.protocol.TMessageType;

import com.google.common.base.CaseFormat;
import com.google.common.collect.ImmutableMap;

/**
 * Provides the metadata of a Thrift service function.
 */
public final class ThriftFunction {

    private enum Type {
        SYNC,
        ASYNC
    }

    private final Object func;
    private final Type type;
    private final Class<?> serviceType;
    private final String name;
    @Nullable
    private final Object implementation;
    @Nullable
    private final TBase<?, ?> result;
    private final TFieldIdEnum[] argFields;
    @Nullable
    private final TFieldIdEnum successField;
    private final Map<Class<Throwable>, TFieldIdEnum> exceptionFields;
    private final Class<?>[] declaredExceptions;

    ThriftFunction(Class<?> serviceType, ProcessFunction<?, ?> func,
                   @Nullable Object implementation) throws Exception {
        this(serviceType, func.getMethodName(), func, Type.SYNC,
             getArgFields(func), getResult(func), getDeclaredExceptions(func), implementation);
    }

    ThriftFunction(Class<?> serviceType, AsyncProcessFunction<?, ?, ?> func,
                   @Nullable Object implementation) throws Exception {
        this(serviceType, func.getMethodName(), func, Type.ASYNC,
             getArgFields(func), getResult(func), getDeclaredExceptions(func), implementation);
    }

    private ThriftFunction(
            Class<?> serviceType, String name, Object func, Type type,
            TFieldIdEnum[] argFields, @Nullable TBase<?, ?> result,
            Class<?>[] declaredExceptions, @Nullable Object implementation) throws Exception {

        this.func = func;
        this.type = type;
        this.serviceType = serviceType;
        this.name = name;
        this.argFields = argFields;
        this.result = result;
        this.declaredExceptions = declaredExceptions;
        this.implementation = implementation;

        // Determine the success and exception fields of the function.
        final ImmutableMap.Builder<Class<Throwable>, TFieldIdEnum> exceptionFieldsBuilder =
                ImmutableMap.builder();
        TFieldIdEnum successField = null;

        if (result != null) { // if not oneway
            @SuppressWarnings("unchecked")
            final Class<? extends TBase<?, ?>> resultType = (Class<? extends TBase<?, ?>>) result.getClass();
            @SuppressWarnings("unchecked")
            final Map<TFieldIdEnum, FieldMetaData> metaDataMap =
                    (Map<TFieldIdEnum, FieldMetaData>) FieldMetaData.getStructMetaDataMap(resultType);

            for (Entry<TFieldIdEnum, FieldMetaData> e : metaDataMap.entrySet()) {
                final TFieldIdEnum key = e.getKey();
                final String fieldName = key.getFieldName();
                if ("success".equals(fieldName)) {
                    successField = key;
                    continue;
                }

                final Class<?> fieldType = resultType.getDeclaredField(fieldName).getType();
                if (Throwable.class.isAssignableFrom(fieldType)) {
                    @SuppressWarnings("unchecked")
                    final Class<Throwable> exceptionFieldType = (Class<Throwable>) fieldType;
                    exceptionFieldsBuilder.put(exceptionFieldType, key);
                }
            }
        }

        this.successField = successField;
        exceptionFields = exceptionFieldsBuilder.build();
    }

    /**
     * Returns {@code true} if this function is a one-way.
     */
    public boolean isOneWay() {
        return result == null;
    }

    /**
     * Returns {@code true} if this function is asynchronous.
     */
    public boolean isAsync() {
        return type == Type.ASYNC;
    }

    /**
     * Returns the type of this function.
     *
     * @return {@link TMessageType#CALL} or {@link TMessageType#ONEWAY}
     */
    public byte messageType() {
        return isOneWay() ? TMessageType.ONEWAY : TMessageType.CALL;
    }

    /**
     * Returns the {@link ProcessFunction}.
     *
     * @throws ClassCastException if this function is asynchronous
     */
    @SuppressWarnings("unchecked")
    public ProcessFunction<Object, TBase<?, ?>> syncFunc() {
        return (ProcessFunction<Object, TBase<?, ?>>) func;
    }

    /**
     * Returns the {@link AsyncProcessFunction}.
     *
     * @throws ClassCastException if this function is synchronous
     */
    @SuppressWarnings("unchecked")
    public AsyncProcessFunction<Object, TBase<?, ?>, Object> asyncFunc() {
        return (AsyncProcessFunction<Object, TBase<?, ?>, Object>) func;
    }

    /**
     * Returns the Thrift service interface this function belongs to.
     */
    public Class<?> serviceType() {
        return serviceType;
    }

    /**
     * Returns the name of this function.
     */
    public String name() {
        return name;
    }

    /**
     * Returns the field that holds the successful result.
     */
    @Nullable
    public TFieldIdEnum successField() {
        return successField;
    }

    /**
     * Returns the field that holds the exception.
     */
    public Collection<TFieldIdEnum> exceptionFields() {
        return exceptionFields.values();
    }

    /**
     * Returns the exceptions declared by this function.
     */
    public Class<?>[] declaredExceptions() {
        return declaredExceptions;
    }

    /**
     * Returns the implementation that this function is associated with.
     */
    @Nullable
    public Object implementation() {
        return implementation;
    }

    /**
     * Returns a new empty arguments instance.
     */
    public TBase<?, ?> newArgs() {
        if (isAsync()) {
            return asyncFunc().getEmptyArgsInstance();
        } else {
            return syncFunc().getEmptyArgsInstance();
        }
    }

    /**
     * Returns a new arguments instance.
     */
    public TBase<?, ?> newArgs(List<Object> args) {
        requireNonNull(args, "args");
        final TBase<?, ?> newArgs = newArgs();
        final int size = args.size();
        for (int i = 0; i < size; i++) {
            ThriftFieldAccess.set(newArgs, argFields[i], args.get(i));
        }
        return newArgs;
    }

    /**
     * Returns a new empty result instance.
     */
    public TBase<?, ?> newResult() {
        assert result != null;
        return result.deepCopy();
    }

    /**
     * Sets the success field of the specified {@code result} to the specified {@code value}.
     */
    public void setSuccess(TBase<?, ?> result, Object value) {
        if (successField != null) {
            ThriftFieldAccess.set(result, successField, value);
        }
    }

    /**
     * Converts the specified {@code result} into a Java object.
     */
    @Nullable
    public Object getResult(TBase<?, ?> result) throws TException {
        for (TFieldIdEnum fieldIdEnum : exceptionFields()) {
            if (ThriftFieldAccess.isSet(result, fieldIdEnum)) {
                throw (TException) ThriftFieldAccess.get(result, fieldIdEnum);
            }
        }

        final TFieldIdEnum successField = successField();
        if (successField == null) { //void method
            return null;
        } else if (ThriftFieldAccess.isSet(result, successField)) {
            return ThriftFieldAccess.get(result, successField);
        } else {
            throw new TApplicationException(
                    TApplicationException.MISSING_RESULT,
                    result.getClass().getName() + '.' + successField.getFieldName());
        }
    }

    @Nullable
    private static TBase<?, ?> getResult(ProcessFunction<?, ?> func) {
        return getResult0(Type.SYNC, func.getClass(), func.getMethodName());
    }

    @Nullable
    private static TBase<?, ?> getResult(AsyncProcessFunction<?, ?, ?> asyncFunc) {
        return getResult0(Type.ASYNC, asyncFunc.getClass(), asyncFunc.getMethodName());
    }

    @Nullable
    private static TBase<?, ?> getResult0(Type type, Class<?> funcClass, String methodName) {

        final String resultTypeName = typeName(type, funcClass, methodName, methodName + "_result");
        try {
            @SuppressWarnings("unchecked")
            final Class<TBase<?, ?>> resultType =
                    (Class<TBase<?, ?>>) Class.forName(resultTypeName, false, funcClass.getClassLoader());
            return resultType.getDeclaredConstructor().newInstance();
        } catch (ClassNotFoundException ignored) {
            // Oneway function does not have a result type.
            return null;
        } catch (Exception e) {
            throw new IllegalStateException("cannot determine the result type of method: " + methodName, e);
        }
    }

    /**
     * Sets the exception field of the specified {@code result} to the specified {@code cause}.
     */
    public boolean setException(TBase<?, ?> result, Throwable cause) {
        final Class<?> causeType = cause.getClass();
        for (Entry<Class<Throwable>, TFieldIdEnum> e : exceptionFields.entrySet()) {
            if (e.getKey().isAssignableFrom(causeType)) {
                ThriftFieldAccess.set(result, e.getValue(), cause);
                return true;
            }
        }
        return false;
    }

    private static TFieldIdEnum[] getArgFields(ProcessFunction<?, ?> func) {
        return getArgFields0(Type.SYNC, func.getClass(), func.getMethodName());
    }

    private static TFieldIdEnum[] getArgFields(AsyncProcessFunction<?, ?, ?> asyncFunc) {
        return getArgFields0(Type.ASYNC, asyncFunc.getClass(), asyncFunc.getMethodName());
    }

    private static TFieldIdEnum[] getArgFields0(Type type, Class<?> funcClass, String methodName) {
        final String fieldIdEnumTypeName = typeName(type, funcClass, methodName, methodName + "_args$_Fields");
        try {
            final Class<?> fieldIdEnumType =
                    Class.forName(fieldIdEnumTypeName, false, funcClass.getClassLoader());
            return (TFieldIdEnum[]) requireNonNull(fieldIdEnumType.getEnumConstants(),
                                                   "field enum may not be empty.");
        } catch (Exception e) {
            throw new IllegalStateException("cannot determine the arg fields of method: " + methodName, e);
        }
    }

    private static Class<?>[] getDeclaredExceptions(ProcessFunction<?, ?> func) {
        return getDeclaredExceptions0(Type.SYNC, func.getClass(), func.getMethodName());
    }

    private static Class<?>[] getDeclaredExceptions(AsyncProcessFunction<?, ?, ?> asyncFunc) {
        return getDeclaredExceptions0(Type.ASYNC, asyncFunc.getClass(), asyncFunc.getMethodName());
    }

    private static Class<?>[] getDeclaredExceptions0(
            Type type, Class<?> funcClass, String methodName) {

        final String ifaceTypeName = typeName(type, funcClass, methodName, "Iface");
        try {
            final Class<?> ifaceType = Class.forName(ifaceTypeName, false, funcClass.getClassLoader());
            final Method[] declaredMethods = ifaceType.getDeclaredMethods();
            // check and convert to camel style, maven plugin only support for underscore to camel
            final String methodNameCamel = methodName.indexOf('_') != -1 ?
                                           CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, methodName)
                                                                         : methodName;
            for (Method m : declaredMethods) {
                if (m.getName().equals(methodName) || m.getName().equals(methodNameCamel)) {
                    return m.getExceptionTypes();
                }
            }
            throw new IllegalStateException("failed to find a method: " + methodName);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "cannot determine the declared exceptions of method: " + methodName, e);
        }
    }

    private static String typeName(Type type, Class<?> funcClass, String methodName, String toAppend) {
        final String funcClassName = funcClass.getName();
        final int serviceClassEndPos = funcClassName.lastIndexOf(
                (type == Type.SYNC ? "$Processor$" : "$AsyncProcessor$") + methodName);

        if (serviceClassEndPos <= 0) {
            throw new IllegalStateException("cannot determine the service class of method: " + methodName);
        }

        return funcClassName.substring(0, serviceClassEndPos) + '$' + toAppend;
    }
}
