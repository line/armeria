.. _DecoratingService: apidocs/index.html?com/linecorp/armeria/server/DecoratingService.html
.. _DecoratingServiceFunction: apidocs/index.html?com/linecorp/armeria/server/DecoratingServiceFunction.html
.. _separating concerns: https://en.wikipedia.org/wiki/Separation_of_concerns
.. _Service: apidocs/index.html?com/linecorp/armeria/server/Service.html
.. _SimpleDecoratingService: apidocs/index.html?com/linecorp/armeria/server/SimpleDecoratingService.html
.. _the decorator pattern: https://en.wikipedia.org/wiki/Decorator_pattern

.. _server-decorator:

Decorating a service
====================

A 'decorating service' (or a 'decorator') is a Service_ that wraps another Service_ to intercept an incoming
request or an outgoing response. As its name says, it is an implementation of `the decorator pattern`_.
Service decoration takes a crucial role in Armeria. A lot of core features such as logging, metrics and
distributed tracing are implemented as decorators and you will also find it useful when `separating concerns`_.

There are basically three ways to write a decorating service:

- Implementing DecoratingServiceFunction_
- Extending SimpleDecoratingService_
- Extending DecoratingService_

Implementing DecoratingServiceFunction_
---------------------------------------

DecoratingServiceFunction_ is a functional interface that greatly simplifies the implementation of a decorating
service. It enables you to write a decorating service with a single lambda expression:

.. code-block:: java

    import com.linecorp.armeria.common.HttpResponse;
    import com.linecorp.armeria.common.HttpStatus;
    import com.linecorp.armeria.server.HttpService;

    ServerBuilder sb = new ServerBuilder();
    HttpService service = ...;
    sb.serviceUnder("/web",
                    service.decorate((delegate, ctx, req) -> {
                        if (!authenticate(req)) {
                            // Authentication failed; fail the request.
                            return HttpResponse.of(HttpStatus.UNAUTHORIZED);
                        }

                        // Authenticated; pass the request to the actual service.
                        return delegate.serve(ctx, req);
                    });

Extending SimpleDecoratingService_
----------------------------------

If your decorator is expected to be reusable, it is recommended to define a new top-level class that extends
SimpleDecoratingService_ :

.. code-block:: java

    public class AuthService extends SimpleDecoratingService<HttpRequest, HttpResponse> {
        public AuthService(Service<HttpRequest, HttpResponse> delegate) {
            super(delegate);
        }

        @Override
        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            if (!authenticate(req)) {
                // Authentication failed; fail the request.
                return HttpResponse.of(HttpStatus.UNAUTHORIZED);

            }

            Service<HttpRequest, HttpResponse> delegate = delegate();
            return delegate.serve(ctx, req);
        }
    }

    ServerBuilder sb = new ServerBuilder();
    // Using a lambda expression:
    sb.serviceUnder("/web", service.decorate(delegate -> new AuthService(delegate)));
    // Using reflection:
    sb.serviceUnder("/web", service.decorate(AuthService.class));

Extending DecoratingService_
----------------------------

So far, we only demonstrated the case where a decorating service does not transform the type of the request and
response. You can do that as well, of course, using DecoratingService_:

.. code-block:: java

    // Transforms a Service<RpcRequest, RpcResponse> into Service<HttpRequest, HttpResponse>.
    public class MyRpcService extends DecoratingService<RpcRequest, RpcResponse,
                                                        HttpRequest, HttpResponse> {

        public MyRpcService(Service<? super RpcRequest, ? extends RpcResponse> delegate) {
            super(delegate);
        }

        @Override
        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            // This method has been greatly simplified for easier understanding.
            // In reality, we will have to do this asynchronously.
            RpcRequest rpcReq = convertToRpcRequest(req);
            RpcResponse rpcRes = delegate().serve(ctx, rpcReq);
            return convertToHttpResponse(rpcRes);
        }

        private RpcRequest convertToRpcRequest(HttpRequest req) { ... }
        private HttpResponse convertToHttpResponse(RpcResponse res) { ... }
    }

Unwrapping decoration
---------------------

Once a Service_ is decorated, the type of the service is not that of the original Service_ anymore.
Therefore, you cannot simply down-cast it to access the method exposed by the original Service_.
Instead, you need to 'unwrap' the decorator using the ``Service.as()`` method:

.. code-block:: java

    MyService service = ...;
    MyDecoratedService decoratedService = service.decorate(...);

    assert !(decoratedService instanceof MyService);
    assert decoratedService.as(MyService.class).get() == service;
    assert decoratedService.as(MyDecoratedService.class).get() == decoratedService;
    assert !decoratedService.as(SomeOtherService.class).isPresent();

``as()`` is especially useful when you are looking for the Service_ instances that implements a certain type
from a server:

.. code-block:: java

    Server server = ...;
    ServerConfig serverConfig = server.config();
    List<ServiceConfig> serviceConfigs = serverConfig.serviceConfigs();
    for (ServiceConfig sc : serviceConfigs) {
        if (sc.service().as(SomeType.class).isPresent()) {
            // Handle the service who implements or extends SomeType.
        }
    }

See also
--------

- :ref:`client-decorator`
