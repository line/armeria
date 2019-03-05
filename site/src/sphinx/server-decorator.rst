.. _separating concerns: https://en.wikipedia.org/wiki/Separation_of_concerns
.. _the decorator pattern: https://en.wikipedia.org/wiki/Decorator_pattern

.. _server-decorator:

Decorating a service
====================

A 'decorating service' (or a 'decorator') is a :api:`Service` that wraps another :api:`Service`
to intercept an incoming request or an outgoing response. As its name says, it is an implementation of
`the decorator pattern`_. Service decoration takes a crucial role in Armeria. A lot of core features
such as logging, metrics and distributed tracing are implemented as decorators and you will also find it
useful when `separating concerns`_.

There are basically three ways to write a decorating service:

- Implementing :api:`DecoratingServiceFunction`
- Extending :api:`SimpleDecoratingService`
- Extending :api:`DecoratingService`

Implementing ``DecoratingServiceFunction``
------------------------------------------

:api:`DecoratingServiceFunction` is a functional interface that greatly simplifies the implementation of
a decorating service. It enables you to write a decorating service with a single lambda expression:

.. code-block:: java

    import com.linecorp.armeria.common.HttpResponse;
    import com.linecorp.armeria.common.HttpStatus;
    import com.linecorp.armeria.server.HttpService;

    ServerBuilder sb = new ServerBuilder();
    HttpService service = ...;
    sb.serviceUnder("/web", service.decorate((delegate, ctx, req) -> {
        if (!authenticate(req)) {
            // Authentication failed; fail the request.
            return HttpResponse.of(HttpStatus.UNAUTHORIZED);
        }

        // Authenticated; pass the request to the actual service.
        return delegate.serve(ctx, req);
    });

Extending ``SimpleDecoratingService``
-------------------------------------

If your decorator is expected to be reusable, it is recommended to define a new top-level class that extends
:api:`SimpleDecoratingService` :

.. code-block:: java

    import com.linecorp.armeria.common.HttpRequest;
    import com.linecorp.armeria.common.HttpResponse;
    import com.linecorp.armeria.server.Service;
    import com.linecorp.armeria.server.ServiceRequestContext;
    import com.linecorp.armeria.server.SimpleDecoratingService;

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

Extending ``DecoratingService``
-------------------------------

So far, we only demonstrated the case where a decorating service does not transform the type of the request and
response. You can do that as well, of course, using :api:`DecoratingService`:

.. code-block:: java

    import com.linecorp.armeria.common.RpcRequest;
    import com.linecorp.armeria.common.RpcResponse;

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

Once a :api:`Service` is decorated, the type of the service is not that of the original :api:`Service`
anymore. Therefore, you cannot simply down-cast it to access the method exposed by the original :api:`Service`.
Instead, you need to 'unwrap' the decorator using the ``Service.as()`` method:

.. code-block:: java

    MyService service = ...;
    MyDecoratedService decoratedService = service.decorate(...);

    assert !(decoratedService instanceof MyService);
    assert decoratedService.as(MyService.class).get() == service;
    assert decoratedService.as(MyDecoratedService.class).get() == decoratedService;
    assert !decoratedService.as(SomeOtherService.class).isPresent();

``as()`` is especially useful when you are looking for the :api:`Service` instances that implements
a certain type from a server:

.. code-block:: java

    import com.linecorp.armeria.server.ServerConfig;
    import java.util.List;

    Server server = ...;
    ServerConfig serverConfig = server.config();
    List<ServiceConfig> serviceConfigs = serverConfig.serviceConfigs();
    for (ServiceConfig sc : serviceConfigs) {
        if (sc.service().as(SomeType.class).isPresent()) {
            // Handle the service who implements or extends SomeType.
        }
    }

.. _server-decorator-service-with-path-mappings:

Decorating ``ServiceWithPathMappings``
--------------------------------------

:api:`ServiceWithPathMappings` is a special variant of :api:`Service` which allows a user to register multiple
routes for a single service. It has a method called ``pathMappings()`` which returns a ``Set`` of
:apiplural:`PathMapping` so that you do not have to specify path mappings when registering your service:

.. code-block:: java

    import com.linecorp.armeria.server.PathMapping;
    import com.linecorp.armeria.server.ServiceWithPathMappings;
    import java.util.HashSet;
    import java.util.Set;

    public class MyServiceWithPathMappings implements ServiceWithPathMappings<HttpRequest, HttpResponse> {
        @Override
        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) { ... }

        @Override
        public Set<PathMapping> pathMappings() {
            Set<PathMapping> pathMappings = new HashSet<>();
            pathMappings.add(PathMapping.of("/services/greet");
            pathMappings.add(PathMapping.of("/services/hello");
            return pathMappings;
        }
    }

    ServerBuilder sb = new ServerBuilder();
    // No path mapping is specified.
    sb.service(new MyServiceWithPathMappings());
    // Override the mappings provided by pathMappings().
    sb.service("/services/hola", new MyServiceWithPathMappings());

However, decorating a :api:`ServiceWithPathMappings` can lead to a compilation error when you attempt to
register it without specifying a path mapping explicitly, because a decorated service is not a
:api:`ServiceWithPathMappings` anymore but just a :api:`Service`:

.. code-block:: java

    import com.linecorp.armeria.server.logging.LoggingService;

    ServerBuilder sb = new ServerBuilder();

    // Works.
    ServiceWithPathMappings<HttpRequest, HttpResponse> service =
            new MyServiceWithPathMappings();
    sb.service(service);

    // Does not work - not a ServiceWithPathMappings anymore due to decoration.
    Service<HttpRequest, HttpResponse> decoratedService =
            service.decorate(LoggingService.newDecorator());
    sb.service(decoratedService); // Compilation error

    // Works if a path mapping is specified explicitly.
    sb.service("/services/bonjour", decoratedService);

Therefore, you need to specify the decorators as extra parameters:

.. code-block:: java

    ServerBuilder sb = new ServerBuilder();
    // Register a service decorated with two decorators at multiple routes.
    sb.service(new MyServiceWithPathMappings(),
               MyDecoratedService::new,
               LoggingService.newDecorator())

A good real-world example of :api:`ServiceWithPathMappings` is :api:`GrpcService`.
See :ref:`server-grpc-decorator` for more information.

See also
--------

- :ref:`client-decorator`
