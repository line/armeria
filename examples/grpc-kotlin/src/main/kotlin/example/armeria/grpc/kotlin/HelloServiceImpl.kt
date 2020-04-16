package example.armeria.grpc.kotlin

import com.linecorp.armeria.server.ServiceRequestContext
import example.armeria.grpc.kotlin.Hello.HelloReply
import example.armeria.grpc.kotlin.Hello.HelloRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext

/**
 * Note that if you want to access a current [ServiceRequestContext] in [HelloServiceImpl],
 * you should initialize [HelloServiceImpl] with [ArmeriaContext].
 */
class HelloServiceImpl : HelloServiceGrpcKt.HelloServiceCoroutineImplBase(Dispatchers.Armeria) {

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
     *
     * @see [Blocking service implementation](https://line.github.io/armeria/server-grpc.html#blocking-service-implementation)
     */
    override suspend fun blockingHello(request: HelloRequest): HelloReply {
        return withContext(ServiceRequestContext.current().blockingTaskExecutor().asCoroutineDispatcher()) {
            try { // Simulate a blocking API call.
                Thread.sleep(3000)
            } catch (ignored: Exception) { // Do nothing.
            }
            // Make sure that current thread is request context aware
            ServiceRequestContext.current()
            buildReply(toMessage(request.name))
        }
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
    override fun bidiHello(requests: Flow<HelloRequest>): Flow<HelloReply> = flow {
        requests.collect { request ->
            ServiceRequestContext.current()
            emit(buildReply(toMessage(request.name)))
        }
    }

    companion object {

        private fun buildReply(message: String): HelloReply = HelloReply.newBuilder().setMessage(message).build()

        private fun toMessage(message: String): String = "Hello, $message!"
    }
}
