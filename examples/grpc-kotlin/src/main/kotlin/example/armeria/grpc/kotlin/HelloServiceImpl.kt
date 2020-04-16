package example.armeria.grpc.kotlin

import com.linecorp.armeria.server.ServiceRequestContext
import example.armeria.grpc.kotlin.Hello.HelloReply
import example.armeria.grpc.kotlin.Hello.HelloRequest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList

class HelloServiceImpl : HelloServiceGrpcKt.HelloServiceCoroutineImplBase() {

    /**
     * Sends a {@link HelloReply} immediately when receiving a request.
     */
    override suspend fun hello(request: HelloRequest): HelloReply = buildReply(toMessage(request.name))

    /**
     * Sends a {@link HelloReply} 3 seconds after receiving a request.
     */
    override suspend fun lazyHello(request: HelloRequest): HelloReply {
        delay(3_000)
        return buildReply(toMessage(request.name))
    }

    /**
     * Sends a {@link HelloReply} using {@code blockingTaskExecutor}.
     *
     * @see <a href="https://line.github.io/armeria/server-grpc.html#blocking-service-implementation">Blocking
     *      service implementation</a>
     */
    override suspend fun blockingHello(request: HelloRequest): HelloReply {
        // Unlike upstream gRPC-Java, Armeria does not run service logic in a separate thread pool by default.
        // Therefore, this method will run in the event loop, which means that you can suffer the performance
        // degradation if you call a blocking API in this method. In this case, you have the following options:
        //
        // 1. Call a blocking API in the blockingTaskExecutor provided by Armeria.
        // 2. Set `GrpcServiceBuilder.useBlockingTaskExecutor(true)` when building your GrpcService.
        // 3. Call a blocking API in the separate thread pool you manage.
        //
        // In this example, we chose the option 1:
        return mockBlockingApiCall(request)
    }

    private suspend fun mockBlockingApiCall(request: HelloRequest): HelloReply = withContext(Dispatchers.IO) {
        try { // Simulate a blocking API call.
            Thread.sleep(3000)
        } catch (ignored: Exception) { // Do nothing.
        }
        buildReply(toMessage(request.name))
    }

    /**
     * Sends 5 {@link HelloReply} responses when receiving a request.
     *
     * @see #lazyHello(HelloRequest, StreamObserver)
     */
    override fun lotsOfReplies(request: HelloRequest): Flow<HelloReply> =
        flow {
            for (i in 1..5) {
                delay(1000)
                emit(buildReply("Hello, ${request.name}! (sequence: $i)"))// emit next value
            }
        }

    /**
     * Sends a {@link HelloReply} when a request has been completed with multiple {@link HelloRequest}s.
     */
    override suspend fun lotsOfGreetings(requests: Flow<HelloRequest>): HelloReply {
        val s =  mutableListOf<String>()
        requests
            .map { request -> request.name }
            .toList(s)

        return buildReply(toMessage(s.joinToString()))
    }

    /**
     * Sends a {@link HelloReply} when each {@link HelloRequest} is received. The response will be completed
     * when the request is completed.
     */
    override fun bidiHello(requests: Flow<HelloRequest>): Flow<HelloReply>  = flow {
        requests.collect { request ->
            emit(buildReply(toMessage(request.name)))
        }
    }

    companion object {

        private fun buildReply(message: String): HelloReply = HelloReply.newBuilder().setMessage(message).build()

        private fun toMessage(message: String): String = "Hello, $message!"
    }

}
