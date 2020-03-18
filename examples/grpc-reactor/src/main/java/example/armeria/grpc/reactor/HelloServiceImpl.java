package example.armeria.grpc.reactor;

import java.time.Duration;
import java.util.stream.Collectors;

import com.linecorp.armeria.server.ServiceRequestContext;

import example.armeria.grpc.reactor.Hello.HelloReply;
import example.armeria.grpc.reactor.Hello.HelloRequest;
import example.armeria.grpc.reactor.ReactorHelloServiceGrpc.HelloServiceImplBase;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public class HelloServiceImpl extends HelloServiceImplBase {

    /**
     * Sends a {@link HelloReply} immediately when receiving a request.
     */
    @Override
    public Mono<HelloReply> hello(Mono<HelloRequest> request) {
        return request.map(it -> buildReply(toMessage(it.getName())));
    }

    /**
     * Sends a {@link HelloReply} 3 seconds after receiving a request.
     */
    @Override
    public Mono<HelloReply> lazyHello(Mono<HelloRequest> request) {
        // You can use the event loop for scheduling a task.
        return request
                .delayElement(Duration.ofSeconds(3),
                              Schedulers.fromExecutor(ServiceRequestContext.current()
                                                                           .contextAwareEventLoop())
                )
                .map(it -> buildReply(toMessage(it.getName())));
    }

    /**
     * Sends a {@link HelloReply} using {@code blockingTaskExecutor}.
     *
     * @see <a href="https://line.github.io/armeria/docs/server-grpc#blocking-service-implementation">Blocking
     *      service implementation</a>
     */
    @Override
    public Mono<HelloReply> blockingHello(Mono<HelloRequest> request) {
        // Unlike upstream gRPC-Java, Armeria does not run service logic in a separate thread pool by default.
        // Therefore, this method will run in the event loop, which means that you can suffer the performance
        // degradation if you call a blocking API in this method. In this case, you have the following options:
        //
        // 1. Call a blocking API in the blockingTaskExecutor provided by Armeria.
        // 2. Set GrpcServiceBuilder.useBlockingTaskExecutor(true) when building your GrpcService.
        // 3. Call a blocking API in the separate thread pool you manage.
        //
        // In this example, we chose the option 1:
        return request
                .publishOn(Schedulers.fromExecutor(ServiceRequestContext.current()
                                                                        .blockingTaskExecutor()))
                .map(it -> {
                    try {
                        // Simulate a blocking API call.
                        Thread.sleep(3000);
                    } catch (Exception ignored) {
                        // Do nothing.
                    }
                    return buildReply(toMessage(it.getName()));
                });
    }

    /**
     * Sends 5 {@link HelloReply} responses when receiving a request.
     */
    @Override
    public Flux<HelloReply> lotsOfReplies(Mono<HelloRequest> request) {
        return request
                .flatMapMany(
                        it -> Flux.interval(Duration.ofSeconds(1))
                                  .take(5)
                                  .map(index -> "Hello, " + it.getName() + "! (sequence: " + (index + 1) + ')')
                )
                // You can make your Flux/Mono publish the signals in the RequestContext-aware executor.
                .publishOn(Schedulers.fromExecutor(ServiceRequestContext.current().contextAwareExecutor()))
                .map(HelloServiceImpl::buildReply);
    }

    /**
     * Sends a {@link HelloReply} when a request has been completed with multiple {@link HelloRequest}s.
     */
    @Override
    public Mono<HelloReply> lotsOfGreetings(Flux<HelloRequest> request) {
        return request
                .map(HelloRequest::getName)
                .collect(Collectors.joining(", "))
                .map(it -> buildReply(toMessage(it)));
    }

    /**
     * Sends a {@link HelloReply} when each {@link HelloRequest} is received. The response will be completed
     * when the request is completed.
     */
    @Override
    public Flux<HelloReply> bidiHello(Flux<HelloRequest> request) {
        return request.map(it -> buildReply(toMessage(it.getName())));
    }

    static String toMessage(String name) {
        return "Hello, " + name + '!';
    }

    private static HelloReply buildReply(Object message) {
        return HelloReply.newBuilder().setMessage(String.valueOf(message)).build();
    }
}
