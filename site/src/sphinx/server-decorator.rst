.. _separating concerns: https://en.wikipedia.org/wiki/Separation_of_concerns
.. _the decorator pattern: https://en.wikipedia.org/wiki/Decorator_pattern

.. _server-decorator:

Decorating a service
====================

A 'decorating service' (or a 'decorator') is a service that wraps another service
to intercept an incoming request or an outgoing response. As its name says, it is an implementation of
`the decorator pattern`_. Service decoration takes a crucial role in Armeria. A lot of core features
such as logging, metrics and distributed tracing are implemented as decorators and you will also find it
useful when `separating concerns`_.

There are basically three ways to write a decorating service:

- Implementing :api:`DecoratingHttpServiceFunction` and :api:`DecoratingRpcServiceFunction`
- Extending :api:`SimpleDecoratingHttpService` and :api:`SimpleDecoratingRpcService`
- Extending :api:`DecoratingService`

Implementing ``DecoratingHttpServiceFunction`` and ``DecoratingRpcServiceFunction``
-----------------------------------------------------------------------------------

:api:`DecoratingHttpServiceFunction` and :api:`DecoratingRpcServiceFunction` are functional interfaces that
greatly simplify the implementation of a decorating service. They enables you to write a decorating service
with a single lambda expression:

.. code-block:: java

    import com.linecorp.armeria.common.HttpResponse;
    import com.linecorp.armeria.common.HttpStatus;
    import com.linecorp.armeria.server.HttpService;

    ServerBuilder sb = Server.builder();
    HttpService service = ...;
    sb.serviceUnder("/web", service.decorate((delegate, ctx, req) -> {
        if (!authenticate(req)) {
            // Authentication failed; fail the request.
            return HttpResponse.of(HttpStatus.UNAUTHORIZED);
        }

        // Authenticated; pass the request to the actual service.
        return delegate.serve(ctx, req);
    });

Extending ``SimpleDecoratingHttpService`` and ``SimpleDecoratingRpcService``
----------------------------------------------------------------------------

If your decorator is expected to be reusable, it is recommended to define a new top-level class that extends
:api:`SimpleDecoratingHttpService` or :api:`SimpleDecoratingRpcService` depending on whether you are
decorating an :api:`HttpService` or an :api:`RpcService`:

.. code-block:: java

    import com.linecorp.armeria.common.HttpRequest;
    import com.linecorp.armeria.common.HttpResponse;
    import com.linecorp.armeria.server.HttpService;
    import com.linecorp.armeria.server.ServiceRequestContext;
    import com.linecorp.armeria.server.SimpleDecoratingHttpService;

    public class AuthService extends SimpleDecoratingHttpService {
        public AuthService(HttpService delegate) {
            super(delegate);
        }

        @Override
        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            if (!authenticate(req)) {
                // Authentication failed; fail the request.
                return HttpResponse.of(HttpStatus.UNAUTHORIZED);
            }

            HttpService delegate = delegate();
            return delegate.serve(ctx, req);
        }
    }

    ServerBuilder sb = Server.builder();
    // Using a lambda expression:
    sb.serviceUnder("/web", service.decorate(delegate -> new AuthService(delegate)));

Extending ``DecoratingService``
-------------------------------

So far, we only demonstrated the case where a decorating service does not transform the type of the request and
response. You can do that as well, of course, using :api:`DecoratingService`:

.. code-block:: java

    import com.linecorp.armeria.server.RpcService;

    // Transforms an RpcService into an HttpService.
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

Once a service is decorated, the type of the service is not that of the original service
anymore. Therefore, you cannot simply down-cast it to access the method exposed by the original service.
Instead, you need to 'unwrap' the decorator using the ``Service.as()`` method:

.. code-block:: java

    MyService service = ...;
    MyDecoratedService decoratedService = service.decorate(...);

    assert !(decoratedService instanceof MyService);
    assert decoratedService.as(MyService.class) == service;
    assert decoratedService.as(MyDecoratedService.class) == decoratedService;
    assert decoratedService.as(SomeOtherService.class) == null;

``as()`` is especially useful when you are looking for the service instances that implements
a certain type from a server:

.. code-block:: java

    import com.linecorp.armeria.server.ServerConfig;
    import java.util.List;

    Server server = ...;
    ServerConfig serverConfig = server.config();
    List<ServiceConfig> serviceConfigs = serverConfig.serviceConfigs();
    for (ServiceConfig sc : serviceConfigs) {
        if (sc.service().as(SomeType.class) != null) {
            // Handle the service who implements or extends SomeType.
        }
    }

.. _server-decorator-service-with-routes:

Decorating ``ServiceWithRoutes``
--------------------------------

:api:`ServiceWithRoutes` is a special variant of service which allows a user to register multiple
routes for a single service. It has a method called ``routes()`` which returns a ``Set`` of
:apiplural:`Route` so that you do not have to specify path when registering your service:

.. code-block:: java

    import com.linecorp.armeria.server.Route;
    import com.linecorp.armeria.server.HttpServiceWithRoutes;
    import java.util.HashSet;
    import java.util.Set;

    public class MyServiceWithRoutes implements HttpServiceWithRoutes {
        @Override
        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) { ... }

        @Override
        public Set<Route> routes() {
            Set<Route> routes = new HashSet<>();
            routes.add(Route.builder().path("/services/greet").build());
            routes.add(Route.builder().path("/services/hello").build());
            return routes;
        }
    }

    ServerBuilder sb = Server.builder();
    // No path is specified.
    sb.service(new MyServiceWithRoutes());
    // Override the path provided by routes().
    sb.service("/services/hola", new MyServiceWithRoutes());

However, decorating a :api:`ServiceWithRoutes` can lead to a compilation error when you attempt to
register it without specifying a path explicitly, because a decorated service is not a
:api:`ServiceWithRoutes` anymore but just a service:

.. code-block:: java

    import com.linecorp.armeria.server.logging.LoggingService;

    ServerBuilder sb = Server.builder();

    // Works.
    HttpServiceWithRoutes service = new MyServiceWithRoutes();
    sb.service(service);

    // Does not work - not a HttpServiceWithRoutes anymore due to decoration.
    HttpService decoratedService = service.decorate(LoggingService.newDecorator());
    sb.service(decoratedService); // Compilation error

    // Works if a path is specified explicitly.
    sb.service("/services/bonjour", decoratedService);

Therefore, you need to specify the decorators as extra parameters:

.. code-block:: java

    ServerBuilder sb = Server.builder();
    // Register a service decorated with two decorators at multiple routes.
    sb.service(new MyServiceWithRoutes(),
               MyDecoratedService::new,
               LoggingService.newDecorator())

A good real-world example of :api:`ServiceWithRoutes` is :api:`GrpcService`.
See :ref:`server-grpc-decorator` for more information.

Decorating multiple services by path mapping
--------------------------------------------

If you want to decorate multiple :apiplural:`Service` by path mapping or router matching, you can specify
decorators using ``decoratorUnder(pathPrefix, ...)`` or ``decorator(Route, ...)``.

.. code-block:: java

    import com.linecorp.armeria.common.HttpHeaderNames;

    VipService vipService = ...;
    MemberService memberService = ...;
    HtmlService htmlService = ...;
    JsService jsService = ...;

    ServerBuilder sb = new ServerBuilder();

    // Register vipService and memberService under '/users' path
    sb.annotatedService("/users/vip", vipService)
      .annotatedService("/users/members", memberService);

    // Decorate all services under '/users' path
    sb.decoratorUnder("/users", (delegate, ctx, req) -> {
        if (!authenticate(req)) {
            return HttpResponse.of(HttpStatus.UNAUTHORIZED);
        }
        return delegate.serve(ctx, req);
    });

    // Register htmlService and jsService under '/public' path
    sb.serviceUnder("/public/html", htmlService)
      .serviceUnder("/public/js", jsService);

    // Decorate services only when a request method is 'GET'
    sb.decorator(Route.builder().get("/public").build(), (delegate, ctx, req) -> {
        final HttpResponse response = delegate.serve(ctx, req);
        ctx.mutateAdditionalResponseHeaders(
                mutator -> mutator.add(HttpHeaderNames.CACHE_CONTROL, "public"));
        return response;
    });

You can also use fluent route builder with ``routeDecorator()`` to match services being decorated.

.. code-block:: java

    ServerBuilder sb = new ServerBuilder();

    // Register vipService and memberService under '/users' path
    sb.annotatedService("/users/vip", vipService)
      .annotatedService("/users/members", memberService);

    // Decorate services under '/users' path with fluent route builder
    sb.routeDecorator()
      .pathPrefix("/users")
      .build((delegate, ctx, req) -> {
          if (!authenticate(req)) {
              return HttpResponse.of(HttpStatus.UNAUTHORIZED);
          }
          return delegate.serve(ctx, req);
      });

    // Register htmlService and jsService under '/public' path
    sb.serviceUnder("/public/html", htmlService)
      .serviceUnder("/public/js", jsService);

    // Decorate services under '/public' path using 'get' method with path pattern
    sb.routeDecorator()
      .get("prefix:/public")
      .build((delegate, ctx, req) -> {
          final HttpResponse response = delegate.serve(ctx, req);
          ctx.mutateAdditionalResponseHeaders(
                  mutator -> mutator.add(HttpHeaderNames.CACHE_CONTROL, "public"));
          return response;
      });


Please refer to :api:`DecoratingServiceBindingBuilder` for more information.

See also
--------

- :ref:`client-decorator`
- :ref:`advanced-custom-attribute`
