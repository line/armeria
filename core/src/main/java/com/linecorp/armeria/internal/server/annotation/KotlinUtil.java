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
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.common.RequestContextUtil;
import com.linecorp.armeria.server.ServiceRequestContext;

@SuppressWarnings("unchecked")
final class KotlinUtil {

    private static final boolean IS_KOTLIN_REFLECTION_PRESENT;

    @Nullable
    private static final Class<? extends Annotation> METADATA_CLASS;

    @Nullable
    private static final Class<?> CONTINUATION_CLASS;

    @Nullable
    private static final MethodHandle CALL_KOTLIN_SUSPENDING_METHOD;

    @Nullable
    private static final Method IS_K_FUNCTION;

    @Nullable
    private static final Method IS_SUSPENDING_FUNCTION;

    @Nullable
    private static final Method IS_RETURN_TYPE_UNIT;

    @Nullable
    private static final Method IS_RETURN_TYPE_NOTHING;

    @Nullable
    private static final Method K_FUNCTION_RETURN_TYPE;

    @Nullable
    private static final Method K_FUNCTION_GENERIC_RETURN_TYPE;

    @Nullable
    private static final Method IS_MARKED_NULLABLE;

    @Nullable
    private static final Method IS_DATA;

    static {
        MethodHandle callKotlinSuspendingMethod = null;
        final String internalCommonPackageName = RequestContextUtil.class.getPackage().getName();
        try {
            final Class<?> coroutineUtilClass =
                    getClass(internalCommonPackageName + ".kotlin.ArmeriaCoroutineUtil");

            callKotlinSuspendingMethod = MethodHandles.lookup().findStatic(
                    coroutineUtilClass, "callKotlinSuspendingMethod",
                    MethodType.methodType(
                            CompletableFuture.class,
                            ImmutableList.of(Method.class, Object.class,
                                             Object[].class, ExecutorService.class,
                                             ServiceRequestContext.class))
            );
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException e) {
            // ignore
        } finally {
            CALL_KOTLIN_SUSPENDING_METHOD = callKotlinSuspendingMethod;
        }

        Method isKFunction = null;
        Method isSuspendingFunction = null;
        Method isReturnTypeUnit = null;
        Method isReturnTypeNothing = null;
        Method kFunctionReturnType = null;
        Method kFunctionGenericReturnType = null;
        Method isMarkedNullable = null;
        Method isData = null;
        try {
            final Class<?> kotlinUtilClass =
                    getClass(internalCommonPackageName + ".kotlin.ArmeriaKotlinUtil");

            isKFunction = kotlinUtilClass.getMethod("isKFunction", Method.class);
            isSuspendingFunction = kotlinUtilClass.getMethod("isSuspendingFunction", Method.class);
            isReturnTypeUnit = kotlinUtilClass.getMethod("isReturnTypeUnit", Method.class);
            isReturnTypeNothing = kotlinUtilClass.getMethod("isReturnTypeNothing", Method.class);
            kFunctionReturnType = kotlinUtilClass.getMethod("kFunctionReturnType", Method.class);
            kFunctionGenericReturnType = kotlinUtilClass.getMethod("kFunctionGenericReturnType", Method.class);
            isMarkedNullable = kotlinUtilClass.getMethod("isMarkedNullable", AnnotatedElement.class);
            isData = kotlinUtilClass.getMethod("isData", Class.class);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            // ignore
        } finally {
            IS_K_FUNCTION = isKFunction;
            IS_SUSPENDING_FUNCTION = isSuspendingFunction;
            IS_RETURN_TYPE_UNIT = isReturnTypeUnit;
            IS_RETURN_TYPE_NOTHING = isReturnTypeNothing;
            K_FUNCTION_RETURN_TYPE = kFunctionReturnType;
            K_FUNCTION_GENERIC_RETURN_TYPE = kFunctionGenericReturnType;
            IS_MARKED_NULLABLE = isMarkedNullable;
            IS_DATA = isData;
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

        Class<?> continuationClass = null;
        try {
            continuationClass = getClass("kotlin.coroutines.Continuation");
        } catch (ClassNotFoundException e) {
            // ignore
        } finally {
            CONTINUATION_CLASS = continuationClass;
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
     * Returns true if the last parameter of a method is a {@code kotlin.coroutines.Continuation}.
     */
    static boolean maybeSuspendingFunction(Method method) {
        return Arrays.stream(method.getParameters())
                     .anyMatch(param -> isContinuation(param.getType()));
    }

    /**
     * Returns true if a method can be represented by a Kotlin function.
     */
    static boolean isKFunction(Method method) {
        try {
            return IS_KOTLIN_REFLECTION_PRESENT &&
                   IS_K_FUNCTION != null &&
                   isKotlinMethod(method) &&
                   (boolean) IS_K_FUNCTION.invoke(null, method);
        } catch (Exception e) {
            return false;
        }
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
     * Returns true if a class is {@code kotlin.coroutines.Continuation}.
     */
    static boolean isContinuation(Class<?> type) {
        return CONTINUATION_CLASS != null && CONTINUATION_CLASS.isAssignableFrom(type);
    }

    /**
     * Returns true if a method is suspending function and it returns {@code kotlin.Unit}.
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

    /**
     * Returns true if a method returns {@code kotlin.Nothing}.
     */
    static boolean isReturnTypeNothing(Method method) {
        try {
            return IS_RETURN_TYPE_NOTHING != null &&
                   (boolean) IS_RETURN_TYPE_NOTHING.invoke(null, method);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * {@link Method#getReturnType} equivalent for Kotlin functions.
     */
    static Class<?> kFunctionReturnType(Method method) {
        assert K_FUNCTION_RETURN_TYPE != null;
        try {
            return (Class<?>) K_FUNCTION_RETURN_TYPE.invoke(null, method);
        } catch (Exception e) {
            return Exceptions.throwUnsafely(e);
        }
    }

    /**
     * {@link Method#getGenericReturnType} equivalent for Kotlin functions.
     */
    static Type kFunctionGenericReturnType(Method method) {
        assert K_FUNCTION_GENERIC_RETURN_TYPE != null;
        try {
            return (Type) K_FUNCTION_GENERIC_RETURN_TYPE.invoke(null, method);
        } catch (Exception e) {
            return Exceptions.throwUnsafely(e);
        }
    }

    /**
     * Returns true if the typeElement can be converted to KType and is marked nullable.
     */
    static boolean isMarkedNullable(AnnotatedElement typeElement) {
        try {
            return IS_MARKED_NULLABLE != null &&
                   (boolean) IS_MARKED_NULLABLE.invoke(null, typeElement);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Returns true if the {@link Class} is a Kotlin data class.
     */
    static boolean isData(Class<?> clazz) {
        try {
            return IS_DATA != null && (boolean) IS_DATA.invoke(null, clazz);
        } catch (Exception e) {
            return false;
        }
    }

    private static Class<?> getClass(String name) throws ClassNotFoundException {
        return Class.forName(name, true, KotlinUtil.class.getClassLoader());
    }

    private KotlinUtil() {}
}
