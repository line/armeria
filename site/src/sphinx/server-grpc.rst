.. _gRPC: https://grpc.io/
.. _gRPC-Web: https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-WEB.md
.. _gRPC-Web-Client: https://github.com/improbable-eng/grpc-web
.. _GrpcSerializationFormats: https://github.com/line/armeria/blob/master/grpc/src/main/java/com/linecorp/armeria/common/grpc/GrpcSerializationFormats.java
.. _GrpcService: apidocs/index.html?com/linecorp/armeria/server/grpc/GrpcService.html
.. _GrpcServiceBuilder: apidocs/index.html?com/linecorp/armeria/server/grpc/GrpcServiceBuilder.html
.. _Protobuf-JSON: https://developers.google.com/protocol-buffers/docs/proto3#json
.. _ServerBuilder: apidocs/index.html?com/linecorp/armeria/server/ServerBuilder.html

.. _server-grpc:

Running a gRPC service
======================

Let's assume we have the following gRPC_ service definition:

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
compatibility with the official gRPC client. If you are going to add non-gRPC services as well, make sure
they are added *before* the GrpcService_ so that it does not take precedence.

``gRPC-Web``
------------

GrpcService_ suppors the gRPC-Web_ protocol, a small modification to the gRPC_ wire format that can be used from
a browser. To enable it for a GrpcService_, add the web formats from GrpcSerializationFormats_ to the
GrpcServiceBuilder_. It is usually convenient to just pass GrpcSerializationFormats_.values().

.. code-block:: java

    ServerBuilder sb = new ServerBuilder();
    ...
    sb.serviceUnder("/",
                    new GrpcServiceBuilder().addService(new MyHelloService())
                                            .supportedSerializationFormats(GrpcSerializationFormats.values())
                                            .build());
    ...
    Server server = sb.build();
    server.start();

The server will support both native gRPC_ and gRPC-Web_ from the same endpoint. Use the unofficial
gRPC-Web-Client_ to access the service from a browser. gRPC-Web_ does not support RPC methods with streaming
requests.

Unframed requests
-----------------

GrpcService_ supports serving unary RPC methods (no streaming request or response) without gRPC_ wire format
framing. This can be useful for gradually migrating an existing HTTP POST based API to gRPC_. As GrpcService_
supports both binary protobuf and Protobuf-JSON_, either legacy protobuf or JSON APIs can be used.

.. code-block:: java

    ServerBuilder sb = new ServerBuilder();
    ...
    sb.serviceUnder("/",
                    new GrpcServiceBuilder().addService(new MyHelloService())
                                            .enableUnframedRequests(true)
                                            .build());
    ...
    Server server = sb.build();
    server.start();

This service's unary methods can be accessed from any HTTP client at e.g., URL ``/grpc.hello.HelloService/Hello``
with Content-Type ``application/protobuf`` for binary protobuf POST body or ``application/json`` for JSON POST
body.

Blocking service implementation
-------------------------------

Unlike upstream gRPC-java, Armeria does not run service logic in a separate threadpool. If your service
implementation requires blocking, either run the individual blocking logic in a threadpool, or just wrap the
entire service implementation in ``RequestContext.current().blockingTaskExecutor().submit``

.. code-block:: java

    public class MyHelloService extends HelloServiceGrpc.HelloServiceImplBase {
        @Override
        public void hello(HelloRequest req, StreamObserver<HelloReply> responseObserver) {
            ServiceRequestContext ctx = (ServiceRequestContext) RequestContext.current();
            ctx.blockingTaskExecutor().submit(() -> {
                Thread.sleep(10000);
                HelloReply reply = HelloReply.newBuilder()
                                             .setMessage("Hello, " + req.getName() + '!')
                                             .build();
                responseObserver.onNext(reply);
                responseObserver.onCompleted();
            });
        }
    }


See also
--------

- :ref:`client-grpc`
