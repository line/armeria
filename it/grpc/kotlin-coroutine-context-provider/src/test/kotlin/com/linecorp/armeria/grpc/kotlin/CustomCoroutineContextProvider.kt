/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.grpc.kotlin

import com.linecorp.armeria.common.kotlin.CoroutineContextProvider
import com.linecorp.armeria.common.util.BlockingTaskExecutor
import com.linecorp.armeria.common.util.ShutdownHooks
import com.linecorp.armeria.server.ServiceRequestContext
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlin.coroutines.CoroutineContext

class CustomCoroutineContextProvider : CoroutineContextProvider {
    companion object {
        val dispatcher: ExecutorCoroutineDispatcher

        init {
            val executor: BlockingTaskExecutor =
                BlockingTaskExecutor.builder()
                    .threadNamePrefix("custom-kotlin-grpc-worker")
                    .numThreads(1)
                    .build()
            dispatcher = executor.asCoroutineDispatcher()
            ShutdownHooks.addClosingTask { executor.shutdown() }
        }
    }

    override fun provide(ctx: ServiceRequestContext): CoroutineContext {
        return dispatcher
    }
}
