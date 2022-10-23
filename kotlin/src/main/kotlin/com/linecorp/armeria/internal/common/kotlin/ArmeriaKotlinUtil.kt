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

@file:JvmName("ArmeriaKotlinUtil")
@file:Suppress("unused")

package com.linecorp.armeria.internal.common.kotlin

import java.lang.reflect.AnnotatedElement
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Parameter
import java.lang.reflect.Type
import kotlin.reflect.full.valueParameters
import kotlin.reflect.jvm.javaType
import kotlin.reflect.jvm.jvmErasure
import kotlin.reflect.jvm.kotlinFunction
import kotlin.reflect.jvm.kotlinProperty

/**
 * Returns true if a method can be represented by a Kotlin function.
 */
internal fun isKFunction(method: Method): Boolean {
    return method.kotlinFunction != null
}

/**
 * Returns true if a method is a suspending function.
 */
internal fun isSuspendingFunction(method: Method): Boolean {
    return method.kotlinFunction
        ?.isSuspend
        ?: return false
}

/**
 * Returns true if a method returns kotlin.Unit.
 */
internal fun isReturnTypeUnit(method: Method): Boolean {
    val kFunction = method.kotlinFunction ?: return false
    return kFunction.returnType.jvmErasure == Unit::class
}

/**
 * Returns true if a method returns kotlin.Nothing.
 */
internal fun isReturnTypeNothing(method: Method): Boolean {
    val kFunction = method.kotlinFunction ?: return false
    return kFunction.returnType.jvmErasure == Nothing::class
}

/**
 * [Method.getReturnType] equivalent for Kotlin functions.
 */
internal fun kFunctionReturnType(method: Method): Class<*> =
    requireNotNull(method.kotlinFunction) { "method is not a kotlin function" }
        .returnType.jvmErasure.java

/**
 * [Method.getGenericReturnType] equivalent for Kotlin functions.
 */
internal fun kFunctionGenericReturnType(method: Method): Type =
    requireNotNull(method.kotlinFunction) { "method is not a kotlin function" }
        .returnType.javaType

/**
 * Returns true if the [element]'s type is marked nullable.
 */
internal fun isMarkedNullable(element: AnnotatedElement): Boolean {
    return when (element) {
        is Field -> element.kotlinProperty?.returnType?.isMarkedNullable ?: false
        is Parameter -> {
            val executable = element.declaringExecutable
            val i = executable.parameters.indexOf(element)
            when (executable) {
                is Method ->
                    executable
                        .kotlinFunction
                        // Should be `valueParameters` to exclude the `this` instance and
                        // the extension receiver parameter.
                        ?.valueParameters
                        ?.get(i)
                        ?.type
                        ?.isMarkedNullable
                        ?: false
                is Constructor<*> ->
                    executable
                        .kotlinFunction
                        ?.valueParameters
                        ?.get(i)
                        ?.type
                        ?.isMarkedNullable
                        ?: false
                else -> false
            }
        }
        is Method -> element.kotlinFunction?.returnType?.isMarkedNullable ?: false
        else -> false
    }
}

/**
 * Returns true if the [Class] is a Kotlin data class.
 */
internal fun isData(clazz: Class<*>): Boolean = clazz.kotlin.isData
