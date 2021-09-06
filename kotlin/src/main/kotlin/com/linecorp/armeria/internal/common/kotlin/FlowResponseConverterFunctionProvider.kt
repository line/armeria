/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.internal.common.kotlin

import com.linecorp.armeria.server.annotation.ResponseConverterFunction
import com.linecorp.armeria.server.annotation.ResponseConverterFunctionProvider
import kotlinx.coroutines.flow.Flow
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

/**
 * Provides [FlowResponseConverterFunction] to annotated services.
 * @see FlowResponseConverterFunction
 */
class FlowResponseConverterFunctionProvider : ResponseConverterFunctionProvider {
    override fun createResponseConverterFunction(
        returnType: Type,
        responseConverter: ResponseConverterFunction
    ): ResponseConverterFunction? =
        returnType
            .toClass()
            ?.let {
                if (Flow::class.java.isAssignableFrom(it)) {
                    FlowResponseConverterFunction(responseConverter)
                } else {
                    null
                }
            }

    private fun Type.toClass(): Class<*>? =
        when (this) {
            is ParameterizedType -> this.rawType as Class<*>
            is Class<*> -> this
            else -> null
        }
}
