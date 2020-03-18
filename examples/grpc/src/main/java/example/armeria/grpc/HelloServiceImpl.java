package example.armeria.grpc;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import com.linecorp.armeria.server.ServiceRequestContext;

import example.armeria.grpc.Hello.HelloReply;
import example.armeria.grpc.Hello.HelloRequest;
import example.armeria.grpc.HelloServiceGrpc.HelloServiceImplBase;
import io.grpc.stub.StreamObserver;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

public class HelloServiceImpl extends HelloServiceImplBase {

    /**
     * Sends a {@link HelloReply} immediately when receiving a request.
     */
    @Override
    public void hello(HelloRequest request, StreamObserver<HelloReply> responseObserver) {
        responseObserver.onNext(buildReply(toMessage(request.getName())));
        responseObserver.onCompleted();
    }

    /**
     * Sends a {@link HelloReply} 3 seconds after receiving a request.
     */
    @Override
    public void lazyHello(HelloRequest request, StreamObserver<HelloReply> responseObserver) {
        // You can use the event loop for scheduling a task.
        ServiceRequestContext.current().contextAwareEventLoop().schedule(() -> {
            responseObserver.onNext(buildReply(toMessage(request.getName())));
            responseObserver.onCompleted();
        }, 3, TimeUnit.SECONDS);
    }

    /**
     * Sends a {@link HelloReply} using {@code blockingTaskExecutor}.
     *
     * @see <a href="https://line.github.io/armeria/docs/server-grpc#blocking-service-implementation">Blocking
     *      service implementation</a>
     */
    @Override
    public void blockingHello(HelloRequest request, StreamObserver<HelloReply> responseObserver) {
        // Unlike upstream gRPC-Java, Armeria does not run service logic in a separate thread pool by default.
        // Therefore, this method will run in the event loop, which means that you can suffer the performance
        // degradation if you call a blocking API in this method. In this case, you have the following options:
        //
        // 1. Call a blocking API in the blockingTaskExecutor provided by Armeria.
        // 2. Set GrpcServiceBuilder.useBlockingTaskExecutor(true) when building your GrpcService.
        // 3. Call a blocking API in the separate thread pool you manage.
        //
        // In this example, we chose the option 1:
        ServiceRequestContext.current().blockingTaskExecutor().submit(() -> {
            try {
                // Simulate a blocking API call.
                Thread.sleep(3000);
            } catch (Exception ignored) {
                // Do nothing.
            }
            responseObserver.onNext(buildReply(toMessage(request.getName())));
            responseObserver.onCompleted();
        });
    }

    /**
     * Sends 5 {@link HelloReply} responses when receiving a request.
     *
     * @see #lazyHello(HelloRequest, StreamObserver)
     */
    @Override
    public void lotsOfReplies(HelloRequest request, StreamObserver<HelloReply> responseObserver) {
        // You can also write this code without Reactor like 'lazyHello' example.
        Flux.interval(Duration.ofSeconds(1))
            .take(5)
            .map(index -> "Hello, " + request.getName() + "! (sequence: " + (index + 1) + ')')
            // You can make your Flux/Mono publish the signals in the RequestContext-aware executor.
            .publishOn(Schedulers.fromExecutor(ServiceRequestContext.current().contextAwareExecutor()))
            .subscribe(message -> {
                           // Confirm this callback is being executed on the RequestContext-aware executor.
                           ServiceRequestContext.current();
                           responseObserver.onNext(buildReply(message));
                       },
                       cause -> {
                           // Confirm this callback is being executed on the RequestContext-aware executor.
                           ServiceRequestContext.current();
                           responseObserver.onError(cause);
                       },
                       () -> {
                           // Confirm this callback is being executed on the RequestContext-aware executor.
                           ServiceRequestContext.current();
                           responseObserver.onCompleted();
                       });
    }

    /**
     * Sends a {@link HelloReply} when a request has been completed with multiple {@link HelloRequest}s.
     */
    @Override
    public StreamObserver<HelloRequest> lotsOfGreetings(StreamObserver<HelloReply> responseObserver) {
        return new StreamObserver<HelloRequest>() {
            final ArrayList<String> names = new ArrayList<>();

            @Override
            public void onNext(HelloRequest value) {
                names.add(value.getName());
            }

            @Override
            public void onError(Throwable t) {
                responseObserver.onError(t);
            }

            @Override
            public void onCompleted() {
                responseObserver.onNext(buildReply(toMessage(String.join(", ", names))));
                responseObserver.onCompleted();
            }
        };
    }

    /**
     * Sends a {@link HelloReply} when each {@link HelloRequest} is received. The response will be completed
     * when the request is completed.
     */
    @Override
    public StreamObserver<HelloRequest> bidiHello(StreamObserver<HelloReply> responseObserver) {
        return new StreamObserver<HelloRequest>() {
            @Override
            public void onNext(HelloRequest value) {
                // Respond to every request received.
                responseObserver.onNext(buildReply(toMessage(value.getName())));
            }

            @Override
            public void onError(Throwable t) {
                responseObserver.onError(t);
            }

            @Override
            public void onCompleted() {
                responseObserver.onCompleted();
            }
        };
    }

    static String toMessage(String name) {
        return "Hello, " + name + '!';
    }

    private static HelloReply buildReply(Object message) {
        return HelloReply.newBuilder().setMessage(String.valueOf(message)).build();
    }
}
