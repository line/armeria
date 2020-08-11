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

@file:JvmName("KotlinUtil")

package com.linecorp.armeria.internal.common.kotlin

import java.lang.reflect.Method
import kotlin.coroutines.Continuation
import kotlin.reflect.jvm.jvmErasure
import kotlin.reflect.jvm.kotlinFunction

/**
 * Returns true if a method is a suspending function.
 */
@Suppress("unused")
fun isSuspendingFunction(method: Method): Boolean {
    return method.kotlinFunction
        ?.isSuspend
        ?: return false
}

/**
 * Returns true if a class is kotlin.coroutines.Continuation.
 */
@Suppress("unused")
fun isContinuation(type: Class<*>): Boolean {
    return Continuation::class.java.isAssignableFrom(type)
}

/**
 * Returns true if a method returns kotlin.Unit.
 */
@Suppress("unused")
fun isReturnTypeUnit(method: Method): Boolean {
    val kFunction = method.kotlinFunction ?: return false
    return kFunction.returnType.jvmErasure == Unit::class
}
