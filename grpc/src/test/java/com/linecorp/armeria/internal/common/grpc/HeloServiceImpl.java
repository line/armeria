package com.linecorp.armeria.internal.common.grpc;

import com.linecorp.armeria.grpc.testing.Hello;
import com.linecorp.armeria.grpc.testing.HelloServiceGrpc;
import io.grpc.stub.StreamObserver;

public class HeloServiceImpl extends HelloServiceGrpc.HelloServiceImplBase {

    @Override
    public void hello(Hello.HelloRequest request, StreamObserver<Hello.HelloResponse> responseObserver) {
        Hello.HelloResponse response = Hello.HelloResponse.newBuilder()
                .setMessage("success")
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}
