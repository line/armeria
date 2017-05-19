.. _GRPC: http://www.grpc.io/
.. _GrpcService: apidocs/index.html?com/linecorp/armeria/server/grpc/GrpcService.html
.. _GrpcServiceBuilder: apidocs/index.html?com/linecorp/armeria/server/grpc/GrpcServiceBuilder.html
.. _ServerBuilder: apidocs/index.html?com/linecorp/armeria/server/ServerBuilder.html

.. _server-grpc:

Running a GRPC service
======================

Let's assume we have the following GRPC_ service definition:

.. code-block:: protobuf

    syntax = "proto3";

    package grpc.hello;

    option java_package = "com.example.grpc.hello";

    service HelloService {
      rpc Hello (HelloRequest) returns (HelloReply) {}
    }

    message HelloRequest {
      string name = 1;
    }

    message HelloReply {
      string message = 1;
    }

The Protobuf compiler will produce some Java code under the ``com.example.grpc.hello`` package.
The most noteworthy one is ``HelloServiceGrpc.java`` which defines the base service class we will implement:

.. code-block:: java

    public class HelloServiceGrpc {
        ...
        public static abstract class HelloServiceImplBase implements BindableService {
            public void hello(HelloRequest req, StreamObserver<HelloReply> responseObserver) {
                asyncUnimplementedUnaryCall(METHOD_HELLO, responseObserver);
            }
            ...
        }
        ...
    }

Our implementation would look like the following:

.. code-block:: java

    public class MyHelloService extends HelloServiceGrpc.HelloServiceImplBase {
        @Override
        public void hello(HelloRequest req, StreamObserver<HelloReply> responseObserver) {
            HelloReply reply = HelloReply.newBuilder()
                                         .setMessage("Hello, " + req.getName() + '!')
                                         .build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        }
    }

``GrpcService``
---------------

Once you've finished the implementation of the service, you need to build a GrpcService_ using
a GrpcServiceBuilder_ and add it to the ServerBuilder_:

.. code-block:: java

    ServerBuilder sb = new ServerBuilder();
    ...
    sb.serviceUnder("/",
                    new GrpcServiceBuilder().addService(new MyHelloService())
                                            .build());
    ...
    Server server = sb.build();
    server.start();

Note that we bound the GrpcService_ under ``/``, which is the catch-all path mapping. This is required for
compatibility with the official GRPC client. If you are going to add non-GRPC services as well, make sure
they are added *before* the GrpcService_ so that it does not take precedence.

No client-side support
----------------------

Unfortunately, Armeria does not have client-side support for GRPC yet. Please consider to volunteer!
