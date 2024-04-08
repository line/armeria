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

import com.linecorp.armeria.common.HttpHeaders
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.ResponseHeaders
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.server.annotation.ResponseConverterFunction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.filterNotNull
import org.reactivestreams.Publisher
import java.lang.reflect.Type

/**
 * A [ResponseConverterFunction] which converts the given [Flow] into [Publisher] and subsequently into
 * [HttpResponse] using [responseConverter].
 *
 * It filters only non-null values from the given [Flow] so that it conforms to the reactive-streams specs.
 *
 * Converting [SharedFlow] with this converter function should be done with special care since [SharedFlow]
 * neither ends nor gets cancelled, which makes the converted [HttpResponse] an infinite stream.
 */
class FlowResponseConverterFunction(
    private val responseConverter: ResponseConverterFunction,
) : ResponseConverterFunction {
    override fun isResponseStreaming(
        returnType: Type,
        produceType: MediaType?,
    ): Boolean {
        // This convert is used only when the return type of an annotated service is `Flow`.
        return true
    }

    override fun convertResponse(
        ctx: ServiceRequestContext,
        headers: ResponseHeaders,
        result: Any?,
        trailers: HttpHeaders,
    ): HttpResponse {
        if (result is Flow<*>) {
            // Reactive Streams doesn't allow emitting null value.
            val publisher = result.filterNotNull().asPublisher(ctx.eventLoop(), ctx)
            return responseConverter.convertResponse(ctx, headers, publisher, trailers)
        }
        return ResponseConverterFunction.fallthrough()
    }
}
