/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.internal.server.annotation;

import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.RequestContext;

@SuppressWarnings("unchecked")
final class KotlinUtil {

    private static final boolean IS_KOTLIN_REFLECTION_PRESENT;

    @Nullable
    private static final Class<? extends Annotation> METADATA_CLASS;

    @Nullable
    private static final MethodHandle CALL_KOTLIN_SUSPENDING_METHOD;

    @Nullable
    private static final Method IS_SUSPENDING_FUNCTION;

    @Nullable
    private static final Method IS_CONTINUATION;

    @Nullable
    private static final Method IS_RETURN_TYPE_UNIT;

    static {
        MethodHandle callKotlinSuspendingMethod = null;
        try {
            final Class<?> coroutineUtilClass =
                    getClass("com.linecorp.armeria.internal.common.kotlin.ArmeriaCoroutineUtil");

            callKotlinSuspendingMethod = MethodHandles.lookup().findStatic(
                    coroutineUtilClass, "callKotlinSuspendingMethod",
                    MethodType.methodType(
                            CompletableFuture.class,
                            ImmutableList.of(Method.class, Object.class,
                                             Object[].class, ExecutorService.class,
                                             RequestContext.class))
            );
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException e) {
            // ignore
        } finally {
            CALL_KOTLIN_SUSPENDING_METHOD = callKotlinSuspendingMethod;
        }

        Method isContinuation = null;
        Method isSuspendingFunction = null;
        Method isReturnTypeUnit = null;
        try {
            final Class<?> kotlinUtilClass =
                    getClass("com.linecorp.armeria.internal.common.kotlin.ArmeriaKotlinUtil");

            isContinuation = kotlinUtilClass.getMethod("isContinuation", Class.class);
            isSuspendingFunction = kotlinUtilClass.getMethod("isSuspendingFunction", Method.class);
            isReturnTypeUnit = kotlinUtilClass.getMethod("isReturnTypeUnit", Method.class);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            // ignore
        } finally {
            IS_CONTINUATION = isContinuation;
            IS_SUSPENDING_FUNCTION = isSuspendingFunction;
            IS_RETURN_TYPE_UNIT = isReturnTypeUnit;
        }

        boolean isKotlinReflectionPresent = false;
        try {
            getClass("kotlin.reflect.full.KClasses");
            isKotlinReflectionPresent = true;
        } catch (ClassNotFoundException e) {
            // ignore
        } finally {
            IS_KOTLIN_REFLECTION_PRESENT = isKotlinReflectionPresent;
        }

        Class<? extends Annotation> metadataClass = null;
        try {
            metadataClass = (Class<? extends Annotation>) getClass("kotlin.Metadata");
        } catch (ClassNotFoundException e) {
            // ignore
        } finally {
            METADATA_CLASS = metadataClass;
        }
    }

    /**
     * Returns a method which invokes Kotlin suspending functions.
     */
    @Nullable
    static MethodHandle getCallKotlinSuspendingMethod() {
        return CALL_KOTLIN_SUSPENDING_METHOD;
    }

    /**
     * Returns true if a method is written in Kotlin.
     */
    static boolean isKotlinMethod(Method method) {
        return METADATA_CLASS != null &&
               method.getDeclaringClass().getAnnotation(METADATA_CLASS) != null;
    }

    /**
     * Returns true if a method is a suspending function.
     */
    static boolean isSuspendingFunction(Method method) {
        try {
            return IS_KOTLIN_REFLECTION_PRESENT &&
                   IS_SUSPENDING_FUNCTION != null &&
                   isKotlinMethod(method) &&
                   (boolean) IS_SUSPENDING_FUNCTION.invoke(null, method);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Returns true if a class is kotlin.coroutines.Continuation.
     */
    static boolean isContinuation(Class<?> type) {
        try {
            return IS_CONTINUATION != null &&
                   (boolean) IS_CONTINUATION.invoke(null, type);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Returns true if a method is suspending function and it returns kotlin.Unit.
     */
    static boolean isSuspendingAndReturnTypeUnit(Method method) {
        try {
            return isSuspendingFunction(method) &&
                   IS_RETURN_TYPE_UNIT != null &&
                   (boolean) IS_RETURN_TYPE_UNIT.invoke(null, method);
        } catch (Exception e) {
            return false;
        }
    }

    private static Class<?> getClass(String name) throws ClassNotFoundException {
        return Class.forName(name, true, KotlinUtil.class.getClassLoader());
    }

    private KotlinUtil() {}
}
