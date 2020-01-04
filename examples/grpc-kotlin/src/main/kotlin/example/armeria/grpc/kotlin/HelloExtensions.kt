package example.armeria.grpc.kotlin

import example.armeria.grpc.kotlin.Hello.HelloReply
import example.armeria.grpc.kotlin.Hello.HelloRequest

fun String.buildHelloReply(): HelloReply = HelloReply.newBuilder().setMessage(this).build()

fun String.buildHelloRequest(): HelloRequest = HelloRequest.newBuilder().setName(this).build()

fun String.hello(): String = "Hello, $this!"
