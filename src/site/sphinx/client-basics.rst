.. _`Server basics`: server-basics.html
.. _`Using Armeria as an HTTP client`: client-http.html
.. _`Clients`: apidocs/index.html?com/linecorp/armeria/client/Clients.html
.. _`ClientBuilder`: apidocs/index.html?com/linecorp/armeria/client/ClientBuilder.html

Client basics
=============

Let's assume we have the following Thrift IDL, served at ``http://127.0.0.1:8080/hello``:

.. code-block:: thrift

    namespace java com.example.thrift

    service HelloService {
        string hello(1:string name)
    }

Making a call starts from creating a client:

.. code-block:: java

    HelloService.Iface helloService = Clients.newClient(
            "tbinary+http://127.0.0.1:8080/hello",
            HelloService.Iface.class); // or AsyncIface.class

    String greeting = helloService.hello("Armerian World");
    assert greeting.equals("Hello, Armerian World!");

Note that we added the serialization format of the call using the ``+`` operator in the scheme part of the URI.
Because we are calling a Thrift server, we should choose: ``tbinary``, ``tcompact``, or ``tjson``.

Since we specified ``HelloService.Iface`` as the client type, ``Clients.newClient()`` will return a synchronous
client implementation.  If we had specified ``HelloService.AsyncIface``, the calling code would have looked
like the following:

.. code-block:: java

    HelloService.AsyncIface helloService = Clients.newClient(
            "tbinary+http://127.0.0.1:8080/hello",
            HelloService.AsyncIface.class);

    helloService.hello("Armerian World", new AsyncMethodCallback<String>() {
        @Override
        public void onComplete(String response) {
            assert response.equals("Hello, Armerian World!");
        }

        @Override
        public void onError(Exception exception) {
            exception.printStackTrace();
        }
    });

You can also use the builder pattern for client construction:

.. code-block:: java

    HelloService.Iface helloService = new ClientBuilder("tbinary+http://127.0.0.1:8080/hello")
            .responseTimeoutMillis(10000)
            .decorator(LoggingClient::new)
            .build(HelloService.Iface.class); // or AsyncIface.class

    String greeting = helloService.hello("Armerian World");
    assert greeting.equals("Hello, Armerian World!");

As you might have noticed already, we decorated the client using ``LoggingClient``, which logs all Thrift calls
and replies. You might be interested in decorating a client using other decorators, for example to gather
metrics. Please also refer to `ClientBuilder`_ for more configuration options.

Next steps
----------
- `Server basics`_ if you did not write a Thrift RPC server with Armeria yet
- `Using Armeria as an HTTP client`_ if you want to send an HTTP request asynchronously
- or you could explore the client-side API documentation:
   - `Clients`_
   - `ClientBuilder`_
