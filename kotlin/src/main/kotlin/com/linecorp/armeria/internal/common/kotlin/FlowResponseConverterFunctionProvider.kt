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
import kotlinx.coroutines.flow.SharedFlow
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

class FlowResponseConverterFunctionProvider : ResponseConverterFunctionProvider {
    override fun createResponseConverterFunction(
        returnType: Type,
        responseConverter: ResponseConverterFunction
    ): ResponseConverterFunction? {
        if (returnType !is ParameterizedType) {
            return null
        }
        val rawType = returnType.rawType as Class<*>
        if (Flow::class.java.isAssignableFrom(rawType) && !SharedFlow::class.java.isAssignableFrom(rawType)) {
            return FlowResponseConverterFunction(responseConverter)
        }
        return null
    }
}
