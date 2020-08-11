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

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.RequestContext;

@SuppressWarnings("unchecked")
final class KotlinUtil {

    private static boolean isKotlinReflectionPresent;

    @Nullable
    private static Class<? extends Annotation> metadataClass;

    @Nullable
    private static MethodHandle callKotlinSuspendingMethod;

    @Nullable
    private static Method isSuspendingFunction;

    @Nullable
    private static Method isContinuation;

    @Nullable
    private static Method isReturnTypeUnit;

    static {
        try {
            final Class<?> coroutineUtilClass =
                    getClass("com.linecorp.armeria.internal.common.kotlin.CoroutineUtil");

            callKotlinSuspendingMethod = MethodHandles.lookup().findStatic(
                    coroutineUtilClass, "callKotlinSuspendingMethod",
                    MethodType.methodType(
                            CompletableFuture.class,
                            ImmutableList.of(Method.class, Object.class, Object[].class, RequestContext.class))
            );
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException e) {
            // ignore
        }

        try {
            final Class<?> kotlinUtilClass =
                    getClass("com.linecorp.armeria.internal.common.kotlin.KotlinUtil");

            isContinuation = kotlinUtilClass.getMethod("isContinuation", Class.class);
            isSuspendingFunction = kotlinUtilClass.getMethod("isSuspendingFunction", Method.class);
            isReturnTypeUnit = kotlinUtilClass.getMethod("isReturnTypeUnit", Method.class);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            // ignore
        }

        try {
            getClass("kotlin.reflect.full.KClasses");
            isKotlinReflectionPresent = true;
        } catch (ClassNotFoundException e) {
            // ignore
        }

        try {
            metadataClass = (Class<? extends Annotation>) getClass("kotlin.Metadata");
        } catch (ClassNotFoundException e) {
            // ignore
        }
    }

    /**
     * Returns a method which invokes Kotlin suspending functions.
     */
    @Nullable
    static MethodHandle getCallKotlinSuspendingMethod() {
        return callKotlinSuspendingMethod;
    }

    /**
     * Returns true if a method is written in Kotlin.
     */
    static boolean isKotlinMethod(Method method) {
        return metadataClass != null &&
               method.getDeclaringClass().getAnnotation(metadataClass) != null;
    }

    /**
     * Returns true if a method is a suspending function.
     */
    static boolean isSuspendingFunction(Method method) {
        try {
            return isKotlinMethod(method) &&
                   isKotlinReflectionPresent &&
                   isSuspendingFunction != null &&
                   (Boolean) isSuspendingFunction.invoke(null, method);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Returns true if a class is kotlin.coroutines.Continuation.
     */
    static boolean isContinuation(Class<?> type) {
        try {
            return isContinuation != null &&
                   (boolean) isContinuation.invoke(null, type);
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
                   isReturnTypeUnit != null &&
                   (boolean) isReturnTypeUnit.invoke(null, method);
        } catch (Exception e) {
            return false;
        }
    }

    private static Class<?> getClass(String name) throws ClassNotFoundException {
        return Class.forName(name, true, KotlinUtil.class.getClassLoader());
    }

    private KotlinUtil() {
    }
}
