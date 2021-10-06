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

package com.linecorp.armeria.grpc.kotlin

import com.linecorp.armeria.common.CommonPools
import com.linecorp.armeria.grpc.kotlin.Hello.HelloReply
import com.linecorp.armeria.grpc.kotlin.Hello.HelloRequest
import com.linecorp.armeria.server.ServiceRequestContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

class HelloServiceImpl : HelloServiceGrpcKt.HelloServiceCoroutineImplBase() {

    /**
     * Sends a [HelloReply] with a small amount of blocking time using `ArmeriaBlockingContext`.
     *
     * @see [Blocking service implementation](https://armeria.dev/docs/server-grpc#blocking-service-implementation)
     */
    override suspend fun shortBlockingHello(request: HelloRequest): HelloReply = withArmeriaBlockingContext {
        try { // Simulate a blocking API call.
            Thread.sleep(10)
        } catch (ignored: Exception) { // Do nothing.
        }

        withContext(blockingDispatcher()) {
            // A request context is propagated by ArmeriaRequestCoroutineContext.
            Thread.sleep(10)
            // Make sure that current thread is request context aware
            ServiceRequestContext.current()
        }
        // Make sure that current thread is request context aware
        ServiceRequestContext.current().addAdditionalResponseHeader("foo", "bar")
        buildReply(toMessage(request.name))
    }

    /**
     * Sends 5 [HelloReply] responses using [armeriaBlockingDispatcher] when receiving a request.
     * @see lazyHello(HelloRequest, StreamObserver)
     */
    override fun blockingLotsOfReplies(request: HelloRequest): Flow<HelloReply> {
        // You can also write this code without Reactor like 'lazyHello' example.
        return flow {
            for (i in 1..5) {
                // Check context between delay and emit
                ServiceRequestContext.current()
                delay(1000)
                ServiceRequestContext.current()
                emit(buildReply("Hello, ${request.name}! (sequence: $i)")) // emit next value
                ServiceRequestContext.current()
            }
        }.flowOn(blockingDispatcher())
    }

    /**
     * Sends 5 [HelloReply] responses with a small amount of blocking time when receiving a request
     * using [armeriaBlockingDispatcher].
     *
     * @see lazyHello(HelloRequest, StreamObserver)
     */
    override fun shortBlockingLotsOfReplies(request: HelloRequest): Flow<HelloReply> {
        // You can also write this code without Reactor like 'lazyHello' example.
        return flow {
            for (i in 1..5) {
                // Check context between delay and emit
                ServiceRequestContext.current()
                delay(10)
                ServiceRequestContext.current()
                emit(buildReply("Hello, ${request.name}! (sequence: $i)")) // emit next value
                ServiceRequestContext.current()
            }
        }.flowOn(armeriaBlockingDispatcher())
    }

    /**
     * Throws an [AuthError], and the exception will be handled
     * by [com.linecorp.armeria.common.grpc.GrpcStatusFunction].
     */
    override suspend fun helloError(request: HelloRequest): HelloReply {
        throw AuthError("${request.name} is unauthenticated")
    }

    companion object {
        fun armeriaBlockingDispatcher(): CoroutineDispatcher =
            ServiceRequestContext.current().blockingTaskExecutor().asCoroutineDispatcher()

        // A blocking dispatcher that does not propagate a request context
        fun blockingDispatcher(): CoroutineDispatcher =
            CommonPools.blockingTaskExecutor().asCoroutineDispatcher()

        suspend fun <T> withArmeriaBlockingContext(block: suspend CoroutineScope.() -> T): T =
            withContext(ServiceRequestContext.current().blockingTaskExecutor().asCoroutineDispatcher(), block)

        private fun buildReply(message: String): HelloReply =
            HelloReply.newBuilder().setMessage(message).build()

        private fun toMessage(message: String): String = "Hello, $message!"
    }
}

class AuthError(override val message: String) : RuntimeException()
