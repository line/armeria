package example.armeria.grpc.kotlin

import example.armeria.grpc.kotlin.Hello.HelloReply
import example.armeria.grpc.kotlin.Hello.HelloRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking

class HelloServiceImpl : HelloServiceGrpcKt.HelloServiceCoroutineImplBase() {
    override suspend fun hello(request: HelloRequest): HelloReply = buildReply(toMessage(request.name))

    override suspend fun lazyHello(request: Hello.HelloRequest): Hello.HelloReply {
        delay(3000)
        return buildReply(toMessage(request.name))
    }

    override suspend fun blockingHello(request: HelloRequest): HelloReply {
        return runBlocking {
                try { // Simulate a blocking API call.
                    Thread.sleep(3000)
                } catch (ignored: Exception) { // Do nothing.
                }
                buildReply(toMessage(request.name))
        }
    }

    override fun lotsOfReplies(request: Hello.HelloRequest): Flow<Hello.HelloReply> =
        flow {
            for (i in 1..5) {
                delay(1000)
                emit(buildReply("Hello, ${request.name}! (sequence: $i)"))// emit next value
            }
        }

    override suspend fun lotsOfGreetings(requests: Flow<Hello.HelloRequest>): Hello.HelloReply {
        val s =  mutableListOf<String>()
        requests
            .map { request -> request.name }
            .toList(s)

        return buildReply(toMessage(s.joinToString()))
    }

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
