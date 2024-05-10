/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.server.kotlin

import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.kotlin.CoroutineContexts
import com.linecorp.armeria.common.kotlin.asCoroutineContext
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.ServiceRequestContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.future.future
import kotlin.coroutines.EmptyCoroutineContext

/**
 * A Coroutine-based [HttpService]
 */
fun interface CoroutineHttpService : HttpService {
    /**
     * Calls [suspendedServe] in a [CoroutineScope] and returns the result as an [HttpResponse].
     */
    override fun serve(
        ctx: ServiceRequestContext,
        req: HttpRequest,
    ): HttpResponse {
        val userContext = CoroutineContexts.get(ctx) ?: EmptyCoroutineContext
        return HttpResponse.of(
            CoroutineScope(
                ctx.asCoroutineContext() + userContext,
            ).future {
                suspendedServe(ctx, req)
            },
        )
    }

    /**
     * Serves an incoming [HttpRequest] in a [CoroutineScope].
     */
    suspend fun suspendedServe(
        ctx: ServiceRequestContext,
        req: HttpRequest,
    ): HttpResponse
}
