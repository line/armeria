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

package example.armeria.contextpropagation.kotlin

import com.google.common.base.Splitter
import com.google.common.collect.Iterables
import com.google.common.util.concurrent.Uninterruptibles.sleepUninterruptibly
import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.AggregatedHttpResponse
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.ServiceRequestContext
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.future.asDeferred
import kotlinx.coroutines.future.await
import kotlinx.coroutines.future.future
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.function.Supplier
import java.util.stream.Collectors

class MainService(private val backendClient: WebClient) : HttpService {
    override fun serve(ctx: ServiceRequestContext, req: HttpRequest): HttpResponse {
        val ctxExecutor = ctx.contextAwareExecutor()
        val response = GlobalScope.future(ctxExecutor.asCoroutineDispatcher()) {

            val numsFromRequest = async { fetchFromRequest(ctx, req) }
            val numsFromDb = async { fetchFromFakeDb(ctx) }
            val nums = awaitAll(numsFromRequest, numsFromDb).flatten()

            // The context is kept after resume.
            require(ServiceRequestContext.current() === ctx)
            require(ctx.eventLoop().inEventLoop())

            val backendResponses =
                awaitAll(
                    *nums.map { num ->
                        // The context is mounted in a thread-local, meaning it is available to all logic such
                        // as tracing.
                        require(ServiceRequestContext.current() === ctx)
                        require(ctx.eventLoop().inEventLoop())

                        backendClient.get("/square/$num").aggregate().asDeferred()
                    }.toTypedArray()
                ).toList()

            // The context is mounted in a thread-local, meaning it is available to all logic such as tracing.
            require(ServiceRequestContext.current() === ctx)
            require(ctx.eventLoop().inEventLoop())

            HttpResponse.of(
                backendResponses.stream()
                    .map(AggregatedHttpResponse::contentUtf8)
                    .collect(Collectors.joining("\n"))
            )
        }
        return HttpResponse.from(response)
    }

    private suspend fun fetchFromRequest(ctx: ServiceRequestContext, req: HttpRequest): List<Long> {
        // The context is mounted in a thread-local, meaning it is available to all logic such as tracing.
        require(ServiceRequestContext.current() === ctx)
        require(ctx.eventLoop().inEventLoop())

        val aggregatedHttpRequest = req.aggregate().await()

        // The context is kept after resume.
        require(ServiceRequestContext.current() === ctx)
        require(ctx.eventLoop().inEventLoop())

        val nums = mutableListOf<Long>()
        for (token in Iterables.concat(
            NUM_SPLITTER.split(aggregatedHttpRequest.path().substring(1)),
            NUM_SPLITTER.split(aggregatedHttpRequest.contentUtf8())
        )) {
            nums.add(token.toLong())
        }
        return nums
    }

    private suspend fun fetchFromFakeDb(ctx: ServiceRequestContext): List<Long> {
        // The context is mounted in a thread-local, meaning it is available to all logic such as tracing.
        require(ServiceRequestContext.current() === ctx)
        require(ctx.eventLoop().inEventLoop())

        // This logic mimics using a blocking method, which would usually be something like a MySQL
        // database query using JDBC.
        return CompletableFuture.supplyAsync(
            Supplier {
                // The context is mounted in a thread-local, meaning it is available to all logic such
                // as tracing.
                require(ServiceRequestContext.current() === ctx)
                require(!ctx.eventLoop().inEventLoop())

                sleepUninterruptibly(Duration.ofMillis(50))
                listOf(23L, -23L)
            },
            // Always run blocking logic on the blocking task executor. By using
            // ServiceRequestContext.blockingTaskExecutor, you also ensure the context is mounted inside the
            // logic (e.g., your DB call will be traced!).
            ctx.blockingTaskExecutor()
        ).await()
    }

    companion object {
        private val NUM_SPLITTER = Splitter.on(',').trimResults().omitEmptyStrings()
    }
}
