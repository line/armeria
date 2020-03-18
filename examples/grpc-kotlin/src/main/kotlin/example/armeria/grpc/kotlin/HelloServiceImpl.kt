package example.armeria.grpc.kotlin

import com.linecorp.armeria.server.ServiceRequestContext
import example.armeria.grpc.kotlin.Hello.HelloReply
import example.armeria.grpc.kotlin.Hello.HelloRequest
import io.grpc.stub.StreamObserver
import java.time.Duration
import java.util.concurrent.TimeUnit
import reactor.core.publisher.Flux
import reactor.core.scheduler.Schedulers

class HelloServiceImpl : HelloServiceGrpc.HelloServiceImplBase() {

    /**
     * Sends a [HelloReply] immediately when receiving a request.
     */
    override fun hello(request: HelloRequest, responseObserver: StreamObserver<HelloReply>) {
        responseObserver.onNext(buildReply(toMessage(request.name)))
        responseObserver.onCompleted()
    }

    override fun lazyHello(request: HelloRequest, responseObserver: StreamObserver<HelloReply>) {
        // You can use the event loop for scheduling a task.
        ServiceRequestContext.current().contextAwareEventLoop().schedule({
            responseObserver.onNext(buildReply(toMessage(request.name)))
            responseObserver.onCompleted()
        }, 3, TimeUnit.SECONDS)
    }

    /**
     * Sends a [HelloReply] using `blockingTaskExecutor`.
     *
     * @see [Blocking service implementation](https://line.github.io/armeria/docs/server-grpc#blocking-service-implementation)
     */
    override fun blockingHello(request: HelloRequest, responseObserver: StreamObserver<HelloReply>) {
        // Unlike upstream gRPC-Java, Armeria does not run service logic in a separate thread pool by default.
        // Therefore, this method will run in the event loop, which means that you can suffer the performance
        // degradation if you call a blocking API in this method. In this case, you have the following options:
        //
        // 1. Call a blocking API in the blockingTaskExecutor provided by Armeria.
        // 2. Set `GrpcServiceBuilder.useBlockingTaskExecutor(true)` when building your GrpcService.
        // 3. Call a blocking API in the separate thread pool you manage.
        //
        // In this example, we chose the option 1:
        ServiceRequestContext.current().blockingTaskExecutor().submit {
            try { // Simulate a blocking API call.
                Thread.sleep(3000)
            } catch (ignored: Exception) { // Do nothing.
            }
            responseObserver.onNext(buildReply(toMessage(request.name)))
            responseObserver.onCompleted()
        }
    }

    /**
     * Sends 5 [HelloReply] responses when receiving a request.
     *
     * @see lazyHello(HelloRequest, StreamObserver)
     */
    override fun lotsOfReplies(request: HelloRequest, responseObserver: StreamObserver<HelloReply>) {
        // You can also write this code without Reactor like 'lazyHello' example.
        Flux.interval(Duration.ofSeconds(1))
                .take(5)
                .map { "Hello, ${request.name}! (sequence: ${it + 1})" }
                // You can make your Flux/Mono publish the signals in the RequestContext-aware executor.
                .publishOn(Schedulers.fromExecutor(ServiceRequestContext.current().contextAwareExecutor()))
                .subscribe({
                    // Confirm this callback is being executed on the RequestContext-aware executor.
                    ServiceRequestContext.current()
                    responseObserver.onNext(buildReply(it))
                },
                {
                    // Confirm this callback is being executed on the RequestContext-aware executor.
                    ServiceRequestContext.current()
                    responseObserver.onError(it)
                },
                {
                    // Confirm this callback is being executed on the RequestContext-aware executor.
                    ServiceRequestContext.current()
                    responseObserver.onCompleted()
                })
    }

    /**
     * Sends a [HelloReply] when a request has been completed with multiple [HelloRequest]s.
     */
    override fun lotsOfGreetings(responseObserver: StreamObserver<HelloReply>): StreamObserver<HelloRequest> {
        return object : StreamObserver<HelloRequest> {
            val names = arrayListOf<String>()

            override fun onNext(value: HelloRequest) {
                names.add(value.name)
            }

            override fun onError(t: Throwable) {
                responseObserver.onError(t)
            }

            override fun onCompleted() {
                responseObserver.onNext(buildReply(toMessage(names.joinToString())))
                responseObserver.onCompleted()
            }
        }
    }

    /**
     * Sends a [HelloReply] when each [HelloRequest] is received. The response will be completed
     * when the request is completed.
     */
    override fun bidiHello(responseObserver: StreamObserver<HelloReply>): StreamObserver<HelloRequest> {
        return object : StreamObserver<HelloRequest> {
            override fun onNext(value: HelloRequest) { // Respond to every request received.
                responseObserver.onNext(buildReply(toMessage(value.name)))
            }

            override fun onError(t: Throwable) {
                responseObserver.onError(t)
            }

            override fun onCompleted() {
                responseObserver.onCompleted()
            }
        }
    }

    companion object {

        private fun buildReply(message: String): HelloReply = HelloReply.newBuilder().setMessage(message).build()

        private fun toMessage(message: String): String = "Hello, $message!"
    }
}
