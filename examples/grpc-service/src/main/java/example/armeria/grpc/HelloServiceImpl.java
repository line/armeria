package example.armeria.grpc;

import java.time.Duration;
import java.util.ArrayList;

import example.armeria.grpc.Hello.HelloReply;
import example.armeria.grpc.Hello.HelloRequest;
import example.armeria.grpc.HelloServiceGrpc.HelloServiceImplBase;
import io.grpc.stub.StreamObserver;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

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
     * Sends an {@link HelloReply} 3 seconds later after receiving a request.
     */
    @Override
    public void lazyHello(HelloRequest request, StreamObserver<HelloReply> responseObserver) {
        // Respond 3 seconds later.
        Mono.delay(Duration.ofSeconds(3))
            .subscribe(unused -> responseObserver.onNext(buildReply(toMessage(request.getName()))),
                       responseObserver::onError, responseObserver::onCompleted);
    }

    /**
     * Sends 5 {@link HelloReply} responses when receiving a request.
     */
    @Override
    public void lotsOfReplies(HelloRequest request, StreamObserver<HelloReply> responseObserver) {
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
