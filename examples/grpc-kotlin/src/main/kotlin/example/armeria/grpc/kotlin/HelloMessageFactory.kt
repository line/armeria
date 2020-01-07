package example.armeria.grpc.kotlin

import example.armeria.grpc.kotlin.Hello.HelloReply
import example.armeria.grpc.kotlin.Hello.HelloRequest

class HelloMessageFactory {

    companion object {

        fun newHelloReply(message: String): HelloReply = HelloReply.newBuilder().setMessage(message).build()

        fun newHelloRequest(message: String): HelloRequest = HelloRequest.newBuilder().setName(message).build()
    }
}
