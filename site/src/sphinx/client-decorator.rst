.. _separating concerns: https://en.wikipedia.org/wiki/Separation_of_concerns
.. _the decorator pattern: https://en.wikipedia.org/wiki/Decorator_pattern

.. _client-decorator:

Decorating a client
===================

A 'decorating client' (or a 'decorator') is a client that wraps another client to intercept an outgoing
request or an incoming response. As its name says, it is an implementation of `the decorator pattern`_.
Client decoration takes a crucial role in Armeria. A lot of core features such as logging, metrics and
distributed tracing are implemented as decorators and you will also find it useful when `separating concerns`_.

There are basically two ways to write a decorating client:

- Implementing :api:`DecoratingHttpClientFunction` and :api:`DecoratingRpcClientFunction`
- Extending :api:`SimpleDecoratingClient`


Implementing ``DecoratingHttpClientFunction`` and ``DecoratingRpcClientFunction``
---------------------------------------------------------------------------------

:api:`DecoratingHttpClientFunction` and :api:`DecoratingRpcClientFunction` are functional interfaces that
greatly simplify the implementation of a decorating client.
They enable you to write a decorating client with a single lambda expression:

.. code-block:: java

    import com.linecorp.armeria.common.HttpRequest;
    import com.linecorp.armeria.common.HttpResponse;

    ClientBuilder cb = Clients.builder(...);
    ...
    cb.decorator((delegate, ctx, req) -> {
        auditRequest(req);
        return delegate.execute(ctx, req);
    });

    MyService.Iface client = cb.build(MyService.Iface.class);

Extending ``SimpleDecoratingHttpClient`` and ``SimpleDecoratingRpcClient``
--------------------------------------------------------------------------

If your decorator is expected to be reusable, it is recommended to define a new top-level class that extends
:api:`SimpleDecoratingHttpClient` or :api:`SimpleDecoratingRpcClient` depending on whether you are decorating an
:api:`HttpClient` or an :api:`RpcClient`:

.. code-block:: java

    import com.linecorp.armeria.client.HttpClient;
    import com.linecorp.armeria.client.SimpleDecoratingHttpClient;

    public class AuditClient extends SimpleDecoratingHttpClient {
        public AuditClient(HttpClient delegate) {
            super(delegate);
        }

        @Override
        public HttpResponse execute(ClientRequestContext ctx, HttpRequest req) throws Exception {
            auditRequest(req);
            return delegate().execute(ctx, req);
        }
    }

    ClientBuilder cb = Clients.builder(...);
    ...
    // Using a lambda expression:
    cb.decorator(delegate -> new AuditClient(delegate));

The order of decoration
-----------------------

The decorators are executed in reverse order of the insertion. The following example shows which order
the decorators are executed by printing the messages.

.. code-block:: java

    import com.linecorp.armeria.client.WebClient;

    ClientBuilder cb = Clients.builder(...);

    // #2 decorator
    cb.decorator((delegate, ctx, req) -> {
        // This is called from #1 decorator.
        System.err.println("Secondly, executed.");
        return delegate.execute(ctx, req);
    });

    // #1 decorator.
    // No matter decorator() or option() is used, decorators are executed in reverse order of the insertion.
    cb.option(ClientOption.DECORATION, ClientDecoration.of((delegate, ctx, req) -> {
        System.err.println("Firstly, executed");
        return delegate.execute(ctx, req); // The delegate, here, is #2 decorator.
                                           // This will call execute(ctx, req) method on #2 decorator.
    });

    WebClient myClient = cb.build(WebClient.class);

The following diagram describes how an HTTP request and HTTP response are gone through decorators:

.. uml::

    @startditaa
    +-----------+  req  +-----------+  req  +-----------+  req  +------------------+    req    +--------------+
    |           |------>|           |------>|           |------>|                  |---------->|              |
    | WebClient |       | #1        |       | #2        |       | Armeria          |           |    Server    |
    |           |  res  | decorator |  res  | decorator |  res  | Networking Layer |    res    |              |
    |           |<------|           |<------|           |<------|                  |<----------|              |
    +-----------+       +-----------+       +-----------+       +------------------+           +--------------+
    @endditaa

If the client is a Thrift client and RPC decorators are used, HTTP decorators and RPC decorators are
separately grouped and executed in reverse order of the insertion:

.. code-block:: java

    ClientBuilder cb = Clients.builder(...);

    // #2 HTTP decorator.
    cb.decorator((delegate, ctx, httpReq) -> {
        System.err.println("Fourthly, executed.");
        ...
    });

    // #2 RPC decorator.
    cb.rpcDecorator((delegate, ctx, rpcReq) -> {
        System.err.println("Secondly, executed.");
        ...
    });

    // #1 HTTP decorator.
    cb.decorator((delegate, ctx, httpReq) -> {
        System.err.println("Thirdly, executed.");
        ...
    });

    // #1 RPC decorator.
    cb.rpcDecorator((delegate, ctx, rpcReq) -> {
        System.err.println("Firstly, executed.");
        ...
    });

An RPC request is converted into an HTTP request before it's sent to a server.
Therefore, RPC decorators are applied before the RPC request is converted and HTTP decorators are applied
after the request is converted into the HTTP request, as described in the following diagram:

.. uml::

    @startditaa
    +--------+ req +-----------+ req +-----------+                     +-----------+ req +-----------+
    |        |---->|           |---->|           |---> RPC to HTTP --->|           |---->|           |----=->
    | Thrift |     | #1 RPC    |     | #2 RPC    |                     | #1 HTTP   |     | #2 HTTP   |
    | Client | res | decorator | res | decorator |                     | decorator | res | decorator |
    |        |<----|           |<----|           |<--- HTTP to RPC <---|           |<----|           |<-=----
    +--------+     +-----------+     +-----------+                     +-----------+     +-----------+
    @endditaa

If the decorator modifies the response (e.g. :api:`DecodingClient`) or spawns more requests
(e.g. :api:`RetryingClient`), the outcome may be different depending on the order of the decorators.
Let's look at the following example that :api:`DecodingClient` and :api:`ContentPreviewingClient`
are used together:

.. code-block:: java

    import com.linecorp.armeria.client.encoding.DecodingClient;
    import com.linecorp.armeria.client.logging.ContentPreviewingClient;

    ClientBuilder cb = Clients.builder(...);
    cb.decorator(DecodingClient.newDecorator());

    // ContentPreviewingClient should be applied after DecodingClient.
    cb.decorator(ContentPreviewingClient.newDecorator(1000));

:api:`DecodingClient` decodes the content of HTTP responses.
:api:`ContentPreviewingClient` is :ref:`content-previewing` of the HTTP response by setting it to the
:api:`RequestLog`. Because it's evaluated after :api:`DecodingClient` from the point of view of an
HTTP response, which means that the response content is set after it's decoded, you will see the decoded
response content preview. If the two decorators are added in the opposite order, you will get the encoded
preview because :api:`ContentPreviewingClient` is evaluated first for the HTTP response.
For :api:`RetryingClient`, please check out :ref:`retry-with-logging`.

See also
--------

- :ref:`server-decorator`
- :ref:`advanced-custom-attribute`
