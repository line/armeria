.. _AsyncMethodCallback: https://github.com/apache/thrift/blob/bd964c7f3460c308161cb6eb90583874a7d8d848/lib/java/src/org/apache/thrift/async/AsyncMethodCallback.java#L22
.. _CompletableFuture: https://docs.oracle.com/javase/10/docs/api/java/util/concurrent/CompletableFuture.html

.. _client-thrift:

Calling a Thrift service
========================

Let's assume we have the following Thrift IDL, served at ``http://127.0.0.1:8080/hello``, just like what we
used in :ref:`server-thrift`:

.. code-block:: thrift

    namespace java com.example.thrift.hello

    service HelloService {
        string hello(1:string name)
    }

Making a call starts from creating a client:

.. code-block:: java

    import com.linecorp.armeria.client.Clients;

    HelloService.Iface helloService = Clients.newClient(
            "tbinary+http://127.0.0.1:8080/hello",
            HelloService.Iface.class); // or AsyncIface.class

    String greeting = helloService.hello("Armerian World");
    assert greeting.equals("Hello, Armerian World!");

Note that we added the serialization format of the call using the ``+`` operator in the scheme part of the URI.
Because we are calling a Thrift server, we should choose: ``tbinary``, ``tcompact``, ``tjson`` or ``ttext``.

Since we specified ``HelloService.Iface`` as the client type, ``Clients.newClient()`` will return a synchronous
client implementation.  If we specified ``HelloService.AsyncIface``, the calling code would have looked like
the following:

.. code-block:: java

    import com.linecorp.armeria.common.thrift.ThriftCompletableFuture;
    import com.linecorp.armeria.common.util.CompletionActions;
    import com.linecorp.armeria.client.Clients;

    HelloService.AsyncIface helloService = Clients.newClient(
            "tbinary+http://127.0.0.1:8080/hello",
            HelloService.AsyncIface.class);

    ThriftCompletableFuture<String> future = new ThriftCompletableFuture<String>();
    helloService.hello("Armerian World", future);

    future.thenAccept(response -> assert response.equals("Hello, Armerian World!"))
          .exceptionally(cause -> {
              cause.printStackTrace();
              return null;
          });

    // You can also wait until the call is finished.
    String reply = future.get();

The example above introduces a new class called :api:`ThriftCompletableFuture`. It is a subtype of Java 8
CompletableFuture_ that implements Thrift AsyncMethodCallback_. Once passed as a callback of an asynchronous
Thrift call, :api:`ThriftCompletableFuture` will complete itself when the reply is received or the call
fails. You'll find it way more convenient to consume the reply than AsyncMethodCallback_ thanks to the rich set
of methods provided by CompletableFuture_.

You can also use the builder pattern for client construction:

.. code-block:: java

    import com.linecorp.armeria.common.HttpRequest;
    import com.linecorp.armeria.common.HttpResponse;

    HelloService.Iface helloService = new ClientBuilder("tbinary+http://127.0.0.1:8080/hello")
            .defaultResponseTimeoutMillis(10000)
            .decorator(LoggingClient.newDecorator())
            .build(HelloService.Iface.class); // or AsyncIface.class

    String greeting = helloService.hello("Armerian World");
    assert greeting.equals("Hello, Armerian World!");

As you might have noticed already, we decorated the client using :api:`LoggingClient`, which logs all
requests and responses. You might be interested in decorating a client using other decorators, for example
to gather metrics. Please also refer to :api:`ClientBuilder` for more configuration options.

See also
--------

- :ref:`server-thrift`
- :ref:`client-decorator`
- :ref:`client-custom-http-headers`
