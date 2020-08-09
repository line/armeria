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
import java.lang.reflect.Method;
import java.lang.reflect.Type;

import javax.annotation.Nullable;

import kotlin.reflect.jvm.ReflectJvmMapping;

@SuppressWarnings("unchecked")
final class KotlinUtil {

    private static boolean isKotlinReflectionPresent;

    @Nullable
    private static Class<? extends Annotation> metadataClass;

    static {
        try {
            Class.forName("kotlin.reflect.full.KClasses", false, KotlinUtil.class.getClassLoader());
            isKotlinReflectionPresent = true;
        } catch (ClassNotFoundException e) {
            // ignore
        }
        try {
            metadataClass = (Class<? extends Annotation>) Class.forName("kotlin.Metadata", false,
                                                                        KotlinUtil.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            // ignore
        }
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
        return isKotlinMethod(method) &&
               isKotlinReflectionPresent &&
               ReflectJvmMapping.getKotlinFunction(method).isSuspend();
    }

    /**
     * Returns true if a method is suspending function and it returns kotlin.Unit.
     */
    static boolean isSuspendingAndReturnTypeUnit(Method method) {
        if (!isSuspendingFunction(method)) {
            return false;
        }
        final Type returnType = ReflectJvmMapping.getJavaType(
                ReflectJvmMapping.getKotlinFunction(method).getReturnType()
        );
        return "kotlin.Unit".equals(returnType.getTypeName());
    }

    private KotlinUtil() {}
}
