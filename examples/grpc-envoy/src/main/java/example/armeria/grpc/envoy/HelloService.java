package example.armeria.grpc.envoy;

import example.armeria.grpc.envoy.Hello.HelloReply;
import example.armeria.grpc.envoy.Hello.HelloRequest;
import example.armeria.grpc.envoy.HelloServiceGrpc.HelloServiceImplBase;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

public class HelloService extends HelloServiceImplBase {

    @Override
    public void hello(HelloRequest request, StreamObserver<HelloReply> responseObserver) {
        if (request.getName().isEmpty()) {
            responseObserver.onError(
                    Status.FAILED_PRECONDITION.withDescription("Name cannot be empty").asRuntimeException());
        } else {
            responseObserver.onNext(buildReply(toMessage(request.getName())));
            responseObserver.onCompleted();
        }
    }

    static String toMessage(String name) {
        return "Hello, " + name + '!';
    }

    private static HelloReply buildReply(Object message) {
        return HelloReply.newBuilder().setMessage(String.valueOf(message)).build();
    }
}
