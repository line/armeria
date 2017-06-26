.. _DecoratingClientFunction: apidocs/index.html?com/linecorp/armeria/client/DecoratingClientFunction.html
.. _separating concerns: https://en.wikipedia.org/wiki/Separation_of_concerns
.. _Client: apidocs/index.html?com/linecorp/armeria/client/Client.html
.. _SimpleDecoratingClient: apidocs/index.html?com/linecorp/armeria/client/SimpleDecoratingClient.html
.. _the decorator pattern: https://en.wikipedia.org/wiki/Decorator_pattern

.. _client-decorator:

Decorating a client
===================

A 'decorating client' (or a 'decorator') is a client that wraps another client to intercept an outgoing
request or an incoming response. As its name says, it is an implementation of `the decorator pattern`_.
Client decoration takes a crucial role in Armeria. A lot of core features such as logging, metrics and
distributed tracing are implemented as decorators and you will also find it useful when `separating concerns`_.

There are basically two ways to write a decorating client:

- Implementing DecoratingClientFunction_
- Extending SimpleDecoratingClient_


Implementing DecoratingClientFunction_
--------------------------------------

DecoratingClientFunction_ is a functional interface that greatly simplifies the implementation of a decorating
client. It enables you to write a decorating client with a single lambda expression:

.. code-block:: java

    import com.linecorp.armeria.common.HttpRequest;
    import com.linecorp.armeria.common.HttpResponse;

    ClientBuilder cb = new ClientBuilder(...);
    ...
    cb.decorator(HttpRequest.class, HttpResponse.class,
                 (delegate, ctx, req) -> {
                     auditRequest(req);
                     return delegate.execute(ctx, req);
                 });

    MyService.Iface client = cb.build(MyService.Iface.class);

Extending SimpleDecoratingClient_
---------------------------------

If your decorator is expected to be reusable, it is recommended to define a new top-level class that extends
SimpleDecoratingClient_ :

.. code-block:: java

    public class AuditClient extends SimpleDecoratingClient<HttpRequest, HttpResponse> {
        public AuditClient(Service<HttpRequest, HttpResponse> delegate) {
            super(delegate);
        }

        @Override
        public HttpResponse execute(ClientRequestContext ctx, HttpRequest req) throws Exception {
            auditRequest(req);
            return delegate().execute(ctx, req);
        }
    }

    ClientBuilder cb = new ClientBuilder(...);
    ...
    // Using a lambda expression:
    cb.decorator(HttpRequest.class, HttpResponse.class,
                 delegate -> new AuditClient(delegate));

See also
--------

- :ref:`server-decorator`
