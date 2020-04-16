package example.armeria.grpc.kotlin

import example.armeria.grpc.kotlin.Hello.HelloReply
import example.armeria.grpc.kotlin.Hello.HelloRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext

class HelloServiceImpl : HelloServiceGrpcKt.HelloServiceCoroutineImplBase() {

    /**
     * Sends a [HelloReply] immediately when receiving a request.
     */
    override suspend fun hello(request: HelloRequest): HelloReply = buildReply(toMessage(request.name))

    /**
     * Sends a [HelloReply] 3 seconds after receiving a request.
     */
    override suspend fun lazyHello(request: HelloRequest): HelloReply {
        delay(3_000)
        return buildReply(toMessage(request.name))
    }

    /**
     * Sends a [HelloReply] using coroutines Dispatchers.IO
     */
    override suspend fun blockingHello(request: HelloRequest): HelloReply {
        return mockBlockingApiCall(request)
    }

    private suspend fun mockBlockingApiCall(request: HelloRequest): HelloReply = withContext(Dispatchers.IO) {
        delay(3_000)
        buildReply(toMessage(request.name))
    }

    /**
     * Sends 5 [HelloReply] responses when receiving a request.
     *
     * @see lazyHello(HelloRequest)
     */
    override fun lotsOfReplies(request: HelloRequest): Flow<HelloReply> =
        flow {
            for (i in 1..5) {
                delay(1_000)
                emit(buildReply("Hello, ${request.name}! (sequence: $i)")) // emit next value
            }
        }

    /**
     * Sends a [HelloReply] when a request has been completed with multiple [HelloRequest]s.
     */
    override suspend fun lotsOfGreetings(requests: Flow<HelloRequest>): HelloReply {
        val s = mutableListOf<String>()
        requests
            .map { request -> request.name }
            .toList(s)

        return buildReply(toMessage(s.joinToString()))
    }

    /**
     * Sends a [HelloReply] when each [HelloRequest] is received. The response will be completed
     * when the request is completed.
     */
    override fun bidiHello(requests: Flow<HelloRequest>): Flow<HelloReply> = flow {
        requests.collect { request ->
            emit(buildReply(toMessage(request.name)))
        }
    }

    companion object {

        private fun buildReply(message: String): HelloReply = HelloReply.newBuilder().setMessage(message).build()

        private fun toMessage(message: String): String = "Hello, $message!"
    }
}
