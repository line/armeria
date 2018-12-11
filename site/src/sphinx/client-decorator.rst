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

- Implementing :api:`DecoratingClientFunction`
- Extending :api:`SimpleDecoratingClient`


Implementing ``DecoratingClientFunction``
-----------------------------------------

:api:`DecoratingClientFunction` is a functional interface that greatly simplifies the implementation of a
decorating client. It enables you to write a decorating client with a single lambda expression:

.. code-block:: java

    import com.linecorp.armeria.common.HttpRequest;
    import com.linecorp.armeria.common.HttpResponse;

    ClientBuilder cb = new ClientBuilder(...);
    ...
    cb.decorator((delegate, ctx, req) -> {
        auditRequest(req);
        return delegate.execute(ctx, req);
    });

    MyService.Iface client = cb.build(MyService.Iface.class);

Extending ``SimpleDecoratingClient``
------------------------------------

If your decorator is expected to be reusable, it is recommended to define a new top-level class that extends
:api:`SimpleDecoratingClient`:

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
    cb.decorator(delegate -> new AuditClient(delegate));

See also
--------

- :ref:`server-decorator`
