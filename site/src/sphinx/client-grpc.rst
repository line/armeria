.. _CompletableFuture: https://docs.oracle.com/javase/10/docs/api/java/util/concurrent/CompletableFuture.html
.. _Futures: https://google.github.io/guava/releases/21.0/api/docs/com/google/common/util/concurrent/Futures.html
.. _ListenableFuture: https://google.github.io/guava/releases/21.0/api/docs/com/google/common/util/concurrent/ListenableFuture.html
.. _gRPC: https://grpc.io/
.. _futures-extra: https://github.com/spotify/futures-extra

.. _client-grpc:

Calling a gRPC service
======================

Let's assume we have the following gRPC_ service definition, served at ``http://127.0.0.1:8080/``, just like
what we used in :ref:`server-grpc`:

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

Making a call starts from creating a client:

.. code-block:: java

    import com.linecorp.armeria.client.Clients;

    HelloServiceBlockingStub helloService = Clients.newClient(
            "gproto+http://127.0.0.1:8080/",
            HelloServiceBlockingStub.class); // or HelloServiceFutureStub.class or HelloServiceStub.class

    HelloRequest request = HelloRequest.newBuilder().setName("Armerian World").build();
    HelloReply reply = helloService.hello(request);
    assert reply.getMessage().equals("Hello, Armerian World!");

Note that we added the serialization format of the call using the ``+`` operator in the scheme part of the URI.
Because we are calling a gRPC_ server, we should choose: ``gproto`` or ``gjson``.

Since we specified ``HelloServiceBlockingStub.class`` as the client type, ``Clients.newClient()`` will return a
synchronous client implementation.  If we specified ``HelloServiceFutureStub``, the calling code would have
looked like the following:

.. code-block:: java

    import com.google.common.util.concurrent.Futures;
    import com.google.common.util.concurrent.ListenableFuture;
    import com.google.common.util.concurrent.MoreExecutors;
    import com.linecorp.armeria.client.Clients;
    import java.util.concurrent.ForkJoinPool;

    HelloServiceFutureStub helloService = Clients.newClient(
            "gproto+http://127.0.0.1:8080/",
            HelloServiceFutureStub.class);

    HelloRequest request = HelloRequest.newBuilder().setName("Armerian World").build();
    ListenableFuture<HelloReply> future = helloService.hello(request);

    Futures.addCallback(future, new FutureCallback<HelloReply>() {
        @Override
        public void onSuccess(HelloReply result) {
            assert result.getMessage().equals("Hello, Armerian World!");
        }

        @Override
        public void onFailure(Throwable t) {
            t.printStackTrace();
        }
    }, MoreExecutors.directExecutor());

    // You can also wait until the call is finished.
    HelloReply reply = future.get();

The asynchronous stub uses Guava's ListenableFuture_ and can be operated on using methods on Futures_. The
futures-extra_ library is very convenient for working with ListenableFuture_ in Java 8, including the ability
to convert it to CompletableFuture_.

gRPC_ also natively supports streaming RPC. If our service definition included this method:

.. code-block:: protobuf

    service HelloService {
      rpc ManyHellos (stream HelloRequest) returns (stream HelloReply) {}
    }

you can use the streaming stub to send and receive multiple responses, in a fully-duplex fashion as necessary.

.. code-block:: java

    import com.linecorp.armeria.client.Clients;
    import java.util.concurrent.CountDownLatch;

    HelloServiceStub helloService = Clients.newClient(
            "gproto+http://127.0.0.1:8080/",
            HelloServiceStub.class);

    // Prepare the observer that will receive the request stream.
    CountDownLatch latch = new CountDownLatch(1);
    StreamObserver<HelloReply> replyStream = new StreamObserver<HelloReply>() {
        @Override
        public void onNext(HelloReply reply) {
            assert reply.getMessage().equals("Hello, Armerian World!");
        }

        @Override
        public void onError(Throwable t) {
            t.printStackTrace();
            latch.countDown();
        }

        @Override
        public void onCompleted() {
            System.out.println("We're done!");
            latch.countDown();
        }
    };

    // Send the request stream.
    StreamObserver<HelloRequest> requestStream = helloService.manyHellos(replyStream);
    HelloRequest request = HelloRequest.newBuilder().setName("Armerian World").build();
    requestStream.onNext(request);
    requestStream.onNext(request);
    requestStream.onCompleted();

    // You may want to wait until the call is finished.
    latch.await();

You can also use the builder pattern for client construction:

.. code-block:: java

    import com.linecorp.armeria.common.HttpRequest;
    import com.linecorp.armeria.common.HttpResponse;

    HelloServiceBlockingStub helloService = new ClientBuilder("gproto+http://127.0.0.1:8080/")
            .defaultResponseTimeoutMillis(10000)
            .decorator(LoggingClient.newDecorator())
            .build(HelloServiceBlockingStub.class); // or HelloServiceFutureStub.class or HelloServiceStub.class

    HelloRequest request = HelloRequest.newBuilder().setName("Armerian World").build();
    HelloReply reply = helloService.hello(request);
    assert reply.getMessage().equals("Hello, Armerian World!");

As you might have noticed already, we decorated the client using :api:`LoggingClient`, which logs all
requests and responses. You might be interested in decorating a client using other decorators, for example
to gather metrics. Please also refer to :api:`ClientBuilder` for more configuration options.

Exception propagation
=====================

If you have enabled ``Flags.verboseResponses()`` in the server being accessed by specifying
``-Dcom.linecorp.armeria.verboseResponses=true`` system property, then any exception during processing
in the server will be returned to the client as a :api:`StatusCauseException` attached to the normal gRPC
``Status``. This can be used for programmatic access to the exception that happened in the server. In this
example, the server always fails with ``throw new IllegalStateException("Failed!");``

.. code-block:: java

    import com.linecorp.armeria.client.Clients;
    import com.linecorp.armeria.common.grpc.StatusCauseException;

    import io.grpc.StatusRuntimeException;

    HelloServiceBlockingStub helloService = Clients.newClient(
            "gproto+http://127.0.0.1:8080/",
            HelloServiceBlockingStub.class); // or HelloServiceFutureStub.class or HelloServiceStub.class

    HelloRequest request = HelloRequest.newBuilder().setName("Armerian World").build();
    try {
        HelloReply reply = helloService.hello(request);
    } catch (StatusRuntimeException e) {
        if (e.getCause() instanceof StatusCauseException) {
            StatusCauseException cause = (StatusCauseException) e.getCause();
            // The name of the class of the exception and its message in the server can be accessed.
            assert cause.getOriginalClassName().equals("java.lang.IllegalStateException");
            assert cause.getOriginalMessage().equals("Failed!");

            // The exception's message is a combination of both the class name and original message.
            assert cause.getMessage().equals("java.lang.IllegalStateException: Failed!");

            // The exception's stack trace is that which occurred when the server threw the exception.
            cause.printStackTrace();

            // Logging frameworks, as used by e.g., LoggingClient, will print the stack trace if configured
            // to do so.

            // Now you know exactly where to look in the server to figure out what may have gone wrong.
        }
    }


See also
--------

- :ref:`server-grpc`
- :ref:`client-decorator`
- :ref:`client-custom-http-headers`
