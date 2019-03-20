package example.armeria.grpc;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.server.ServiceRequestContext;

import example.armeria.grpc.Hello.HelloReply;
import example.armeria.grpc.Hello.HelloRequest;
import example.armeria.grpc.HelloServiceGrpc.HelloServiceImplBase;
import io.grpc.stub.StreamObserver;
import reactor.core.publisher.Flux;

public class HelloServiceImpl extends HelloServiceImplBase {

    /**
     * Sends an {@link HelloReply} immediately when receiving a request.
     */
    @Override
    public void hello(HelloRequest request, StreamObserver<HelloReply> responseObserver) {
        responseObserver.onNext(buildReply(toMessage(request.getName())));
        responseObserver.onCompleted();
    }

    /**
     * Sends an {@link HelloReply} 3 seconds after receiving a request.
     */
    @Override
    public void lazyHello(HelloRequest request, StreamObserver<HelloReply> responseObserver) {
        // You can use the event loop for scheduling.
        RequestContext.current().contextAwareEventLoop().schedule(() -> {
            responseObserver.onNext(buildReply(toMessage(request.getName())));
            responseObserver.onCompleted();
        }, 3, TimeUnit.SECONDS);
    }

    /**
     * Sends an {@link HelloReply} using {@code blockingTaskExecutor}.
     *
     * @see <a href="https://line.github.io/armeria/server-grpc.html#blocking-service-implementation">
     *      Blocking service implementation</a>
     */
    @Override
    public void blockingHello(HelloRequest request, StreamObserver<HelloReply> responseObserver) {
        // Unlike upstream gRPC-java, Armeria does not run service logic in a separate thread pool by default.
        // Thus this method will run in the event loop, which means that you can suffer the performance
        // degradation if you call a blocking API in this method. In this case, you have the following options:
        //
        // 1. Call a blocking API in the blockingTaskExecutor provided by Armeria.
        // 2. Set GrpcServiceBuilder.useBlockingTaskExecutor(true) when building your GrpcService.
        // 3. Call a blocking API in the separate thread pool you managed.
        //
        // You can see the option 1 in this example.
        final ServiceRequestContext ctx = RequestContext.current();
        ctx.blockingTaskExecutor().submit(() -> {
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
     */
    @Override
    public void lotsOfReplies(HelloRequest request, StreamObserver<HelloReply> responseObserver) {
        // You can also write this code without Reactor like 'lazyHello' example.
        Flux.interval(Duration.ofSeconds(1))
            .take(5)
            .map(index -> "Hello, " + request.getName() + "! (sequence: " + (index + 1) + ')')
            .subscribe(message -> responseObserver.onNext(buildReply(message)),
                       responseObserver::onError, responseObserver::onCompleted);
    }

    /**
     * Sends an {@link HelloReply} when a request has been completed with multiple {@link HelloRequest}s.
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
     * Sends an {@link HelloReply} when each {@link HelloRequest} is received. The response will be completed
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
