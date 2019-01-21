.. _gRPC: https://grpc.io/
.. _gRPC-Web: https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-WEB.md
.. _gRPC-Web-Client: https://github.com/improbable-eng/grpc-web
.. _protobuf-gradle-plugin: https://github.com/google/protobuf-gradle-plugin
.. _Protobuf-JSON: https://developers.google.com/protocol-buffers/docs/proto3#json
.. _the gRPC-Java README: https://github.com/grpc/grpc-java/blob/master/README.md#download

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

.. note::

    It is recommended to use the build plugin as explained in `the gRPC-Java README`_ rather than
    running ``protoc`` manually.

``GrpcService``
---------------

Once you've finished the implementation of the service, you need to build a :api:`GrpcService` using
a :api:`GrpcServiceBuilder` and add it to the :api:`ServerBuilder`:

.. code-block:: java

    import com.linecorp.armeria.server.Server;
    import com.linecorp.armeria.server.ServerBuilder;
    import com.linecorp.armeria.server.grpc.GrpcServiceBuilder;

    ServerBuilder sb = new ServerBuilder();
    ...
    sb.service(new GrpcServiceBuilder().addService(new MyHelloService())
                                       .build());
    ...
    Server server = sb.build();
    server.start();

.. note::

    We bound the :api:`GrpcService` without specifying any path mappings. It is because :api:`GrpcService`
    implements :api:`ServiceWithPathMappings`, which dynamically provides path mappings by itself.

``gRPC-Web``
------------

:api:`GrpcService` supports the gRPC-Web_ protocol, a small modification to the gRPC_ wire format
that can be used from a browser. To enable it for a :api:`GrpcService`, add the web formats from
:api:`GrpcSerializationFormats` to the :api:`GrpcServiceBuilder`. It is usually convenient
to just pass ``GrpcSerializationFormats.values()``.

.. code-block:: java

    import com.linecorp.armeria.server.grpc.GrpcSerializationFormats;

    ServerBuilder sb = new ServerBuilder();
    ...
    sb.service(new GrpcServiceBuilder().addService(new MyHelloService())
                                       .supportedSerializationFormats(GrpcSerializationFormats.values())
                                       .build());
    ...
    Server server = sb.build();
    server.start();

The server will support both native gRPC_ and gRPC-Web_ from the same endpoint. Use the unofficial
gRPC-Web-Client_ to access the service from a browser. gRPC-Web_ does not support RPC methods with streaming
requests.

If the origin of the Javascript and API server are different, gRPC-Web-Client_ first sends ``preflight``
requests by the HTTP ``OPTIONS`` method, in order to determine whether the actual request is safe to send
in terms of CORS. Armeria provides :api:`CorsService` to handle this requests, so you need to decorate it when
you build a :api:`GrpcService`:

.. code-block:: java

    import com.linecorp.armeria.server.cors.CorsServiceBuilder;

    ServerBuilder sb = new ServerBuilder();
    ...

    final CorsServiceBuilder corsBuilder =
            CorsServiceBuilder.forOrigin("http://foo.com")
                              .allowRequestMethods(HttpMethod.POST) // Allow POST method.
                              // Allow Content-type and X-GRPC-WEB headers.
                              .allowRequestHeaders(HttpHeaderNames.CONTENT_TYPE,
                                                   HttpHeaderNames.of("X-GRPC-WEB"));

    sb.service(new GrpcServiceBuilder().addService(new MyHelloService())
                                       .supportedSerializationFormats(GrpcSerializationFormats.values())
                                       .build(), corsBuilder.newDecorator());
    ...
    Server server = sb.build();
    server.start();

Please refer to :ref:`server-cors` for more information.

Unframed requests
-----------------

:api:`GrpcService` supports serving unary RPC methods (no streaming request or response) without
gRPC_ wire format framing. This can be useful for gradually migrating an existing HTTP POST based API to gRPC_.
As :api:`GrpcService` supports both binary protobuf and Protobuf-JSON_, either legacy protobuf or JSON APIs
can be used.

.. code-block:: java

    ServerBuilder sb = new ServerBuilder();
    ...
    sb.service(new GrpcServiceBuilder().addService(new MyHelloService())
                                       .enableUnframedRequests(true)
                                       // Needed to support JSON in addition to binary
                                       .supportedSerializationFormats(GrpcSerializationFormats.PROTO,
                                                                      GrpcSerializationFormats.JSON)
                                       .build());
    ...
    Server server = sb.build();
    server.start();

This service's unary methods can be accessed from any HTTP client at e.g., URL ``/grpc.hello.HelloService/Hello``
with Content-Type ``application/protobuf`` for binary protobuf POST body or ``application/json; charset=utf-8``
for JSON POST body.

Blocking service implementation
-------------------------------

Unlike upstream gRPC-java, Armeria does not run service logic in a separate thread pool by default. If your
service implementation requires blocking, either run the individual blocking logic in a thread pool, wrap the
entire service implementation in ``RequestContext.current().blockingTaskExecutor().submit``, or set
``GrpcServiceBuilder.useBlockingTaskExecutor()`` so the above happens automatically for all service methods
and lifecycle callbacks.

.. code-block:: java

    import com.linecorp.armeria.common.RequestContext;
    import com.linecorp.armeria.server.ServiceRequestContext;

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

.. code-block:: java

    import com.linecorp.armeria.common.RequestContext;
    import com.linecorp.armeria.server.ServiceRequestContext;
    import com.linecorp.armeria.server.grpc.GrpcServiceBuilder;

    public class MyHelloService extends HelloServiceGrpc.HelloServiceImplBase {
        @Override
        public void hello(HelloRequest req, StreamObserver<HelloReply> responseObserver) {
            Thread.sleep(10000);
            HelloReply reply = HelloReply.newBuilder()
                                         .setMessage("Hello, " + req.getName() + '!')
                                         .build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
        }
    }

    ServerBuilder sb = new ServerBuilder();
    sb.service(new GrpcServiceBuilder().addService(new MyHelloService())
                                       // All service methods will be run within
                                       // the blocking executor.
                                       .useBlockingTaskExecutor(true)
                                       .build());

Exception propagation
=====================

It can be very useful to enable ``Flags.verboseResponses()`` in your server by specifying the
``-Dcom.linecorp.armeria.verboseResponses=true`` system property, which will automatically return
information about an exception thrown in the server to gRPC clients. Armeria clients will automatically
convert it back into an exception for structured logging, etc. This response will include information about
the actual source code in the server - make sure it is safe to send such potentially sensitive information
to all your clients before enabling this flag!

See more details at :ref:`client-grpc`.

See also
--------

- :ref:`client-grpc`
