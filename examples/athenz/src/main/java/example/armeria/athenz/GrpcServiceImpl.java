package example.armeria.athenz;

import com.linecorp.armeria.server.athenz.RequiresAthenzRole;

import example.armeria.grpc.Hello.HelloReply;
import example.armeria.grpc.Hello.HelloRequest;
import example.armeria.grpc.HelloServiceGrpc.HelloServiceImplBase;
import io.grpc.stub.StreamObserver;

final class GrpcServiceImpl extends HelloServiceImplBase {
    @RequiresAthenzRole(resource = "greeting", action = "hello")
    @Override
    public void hello(HelloRequest request, StreamObserver<HelloReply> responseObserver) {
        final String name = request.getName();
        final HelloReply reply = HelloReply.newBuilder()
                                           .setMessage("Hello, " + name + '!')
                                           .build();
        responseObserver.onNext(reply);
        responseObserver.onCompleted();
    }
}
