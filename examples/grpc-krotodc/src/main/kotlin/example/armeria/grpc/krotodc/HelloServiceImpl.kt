package example.armeria.grpc.krotodc

import com.linecorp.armeria.server.ServiceRequestContext
import example.armeria.grpc.krotodc.krotodc.HelloReply
import example.armeria.grpc.krotodc.krotodc.HelloRequest
import example.armeria.grpc.krotodc.krotodc.HelloServiceGrpcKroto
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext

class HelloServiceImpl : HelloServiceGrpcKroto.HelloServiceCoroutineImplBase() {
    /**
     * Sends a [HelloReply] immediately when receiving a request.
     */
    override suspend fun hello(request: HelloRequest): HelloReply {
        // Make sure that current thread is request context aware
        ServiceRequestContext.current()
        return buildReply(toMessage(request.name))
    }

    override suspend fun lazyHello(request: HelloRequest): HelloReply {
        delay(3000L)
        ServiceRequestContext.current()
        return buildReply(toMessage(request.name))
    }

    /**
     * Sends a [HelloReply] using `blockingTaskExecutor`.
     * You can remove the [[withArmeriaBlockingContext]] block when your gRPC service is built with
     * [[com.linecorp.armeria.server.grpc.GrpcServiceBuilder.useBlockingTaskExecutor]].
     *
     * @see [Blocking service implementation](https://armeria.dev/docs/server-grpc#blocking-service-implementation)
     */
    override suspend fun blockingHello(request: HelloRequest): HelloReply =
        withArmeriaBlockingContext {
            try {
                // Simulate a blocking API call.
                Thread.sleep(3000)
            } catch (ignored: Exception) {
                // Do nothing.
            }
            // Make sure that current thread is request context aware
            ServiceRequestContext.current()
            buildReply(toMessage(request.name))
        }

    /**
     * Sends 5 [HelloReply] responses when receiving a request.
     *
     * @see lazyHello(HelloRequest, StreamObserver)
     */
    override fun lotsOfReplies(request: HelloRequest): Flow<HelloReply> {
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
        }
    }

    /**
     * Sends a [HelloReply] when a request has been completed with multiple [HelloRequest]s.
     */
    override suspend fun lotsOfGreetings(requests: Flow<HelloRequest>): HelloReply {
        val names = mutableListOf<String>()
        requests.map { it.name }.toList(names)
        // Make sure that current thread is request context aware
        ServiceRequestContext.current()
        return buildReply(toMessage(names.joinToString()))
    }

    /**
     * Sends a [HelloReply] when each [HelloRequest] is received. The response will be completed
     * when the request is completed.
     */
    override fun bidiHello(requests: Flow<HelloRequest>): Flow<HelloReply> =
        flow {
            requests.collect { request ->
                ServiceRequestContext.current()
                emit(buildReply(toMessage(request.name)))
            }
        }

    companion object {
        fun armeriaBlockingDispatcher(): CoroutineDispatcher =
            ServiceRequestContext.current().blockingTaskExecutor().asCoroutineDispatcher()

        suspend fun <T> withArmeriaBlockingContext(block: suspend CoroutineScope.() -> T): T =
            withContext(ServiceRequestContext.current().blockingTaskExecutor().asCoroutineDispatcher(), block)

        private fun buildReply(message: String): HelloReply = HelloReply(message = message)

        private fun toMessage(message: String): String = "Hello, $message!"
    }
}
