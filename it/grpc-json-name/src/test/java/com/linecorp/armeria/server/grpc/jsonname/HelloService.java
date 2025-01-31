package com.linecorp.armeria.server.grpc.jsonname;

import io.grpc.stub.StreamObserver;
import testing.grpc.jsonname.HelloReply;
import testing.grpc.jsonname.HelloRequest;
import testing.grpc.jsonname.HelloServiceGrpc;

public class HelloService extends HelloServiceGrpc.HelloServiceImplBase {

    @Override
    public void hello(HelloRequest req, StreamObserver<HelloReply> responseObserver) {
        HelloReply reply = HelloReply.newBuilder()
                .setMessage("Hello, " + req.getNameInput() + '!')
                .build();
        responseObserver.onNext(reply);
        responseObserver.onCompleted();
    }
}
