.. _server-cors:

Configuring CORS
================

Armeria provides a way to configure Cross-origin resource sharing (CORS) policy for specific origins or
any origin via :api:`CorsServiceBuilder`. For more information about CORS,
visit `Wikipedia's CORS page <https://en.wikipedia.org/wiki/Cross-origin_resource_sharing>`_.


Allowing any origin
-------------------
To configure CORS Service allowing any origin (*), use ``CorsServiceBuilder.forAnyOrigin()``, e.g.

.. code-block:: java

    import com.linecorp.armeria.common.HttpMethod;
    import com.linecorp.armeria.server.HttpService;
    import com.linecorp.armeria.server.ServerBuilder;
    import com.linecorp.armeria.server.cors.CorsServiceBuilder;

    HttpService myService = (ctx, req) -> ...;
    Function<Service<HttpRequest, HttpResponse>, CorsService> corsService =
            CorsServiceBuilder.forAnyOrigin()
                              .allowCredentials()
                              .allowRequestMethods(HttpMethod.POST, HttpMethod.GET)
                              .allowRequestHeaders("allow_request_header")
                              .exposeHeaders("expose_header_1", "expose_header_2")
                              .preflightResponseHeader("x-preflight-cors", "Hello CORS")
                              .newDecorator();
    ServerBuilder sb = Server.builder()
                             .service("/message", myService.decorate(corsService));

Allowing specific origins
-------------------------
To configure CORS Service allowing specific origins, use ``CorsServiceBuilder.forOrigins()`` or
``CorsServiceBuilder.forOrigin()``, e.g.

.. code-block:: java

    HttpService myService = (ctx, req) -> ...;
    Function<Service<HttpRequest, HttpResponse>, CorsService> corsService =
            CorsServiceBuilder.forOrigins("http://example.com")
                              .allowCredentials()
                              .allowNullOrigin() // 'Origin: null' will be accepted.
                              .allowRequestMethods(HttpMethod.POST, HttpMethod.GET)
                              .allowRequestHeaders("allow_request_header")
                              .exposeHeaders("expose_header_1", "expose_header_2")
                              .preflightResponseHeader("x-preflight-cors", "Hello CORS")
                              .newDecorator();
    ServerBuilder sb = Server.builder()
                             .service("/message", myService.decorate(corsService));


Applying different policies for different origins
-------------------------------------------------
To configure different policies for different groups of origins, use ``andForOrigins()`` or ``andForOrigin()``
method which creates a new :api:`ChainedCorsPolicyBuilder` with the specific origins.
Call ``and()`` to return to :api:`CorsServiceBuilder` once you are done with building a policy, e.g.

.. code-block:: java

    HttpService myService = (ctx, req) -> ...;
    Function<Service<HttpRequest, HttpResponse>, CorsService> corsService =
            CorsServiceBuilder.forOrigins("http://example.com")
                              .allowCredentials()
                              .allowNullOrigin() // 'Origin: null' will be accepted.
                              .allowRequestMethods(HttpMethod.POST, HttpMethod.GET)
                              .allowRequestHeaders("allow_request_header")
                              .exposeHeaders("expose_header_1", "expose_header_2")
                              .preflightResponseHeader("x-preflight-cors", "Hello CORS")
                              .maxAge(3600)
                              .andForOrigins("http://example2.com")
                              .allowCredentials()
                              .allowRequestMethods(HttpMethod.GET)
                              .allowRequestHeaders("allow_request_header2")
                              .exposeHeaders("expose_header_3", "expose_header_4")
                              .and()
                              .newDecorator();
    ServerBuilder sb = Server.builder()
                             .service("/message", myService.decorate(corsService));

You can also directly add a :api:`CorsPolicy` created by a :api:`CorsPolicyBuilder`, e.g.

.. code-block:: java

    import com.linecorp.armeria.server.cors.CorsPolicyBuilder;

    HttpService myService = (ctx, req) -> ...;
    Function<Service<HttpRequest, HttpResponse>, CorsService> corsService =
            CorsServiceBuilder.forOrigins("http://example.com")
                              .allowCredentials()
                              .allowNullOrigin() // 'Origin: null' will be accepted.
                              .allowRequestMethods(HttpMethod.POST, HttpMethod.GET)
                              .allowRequestHeaders("allow_request_header")
                              .exposeHeaders("expose_header_1", "expose_header_2")
                              .preflightResponseHeader("x-preflight-cors", "Hello CORS")
                              .maxAge(3600)
                              .addPolicy(new CorsPolicyBuilder("http://example2.com")
                                                 .allowCredentials()
                                                 .allowRequestMethods(HttpMethod.GET)
                                                 .allowRequestHeaders("allow_request_header2")
                                                 .exposeHeaders("expose_header_3", "expose_header_4")
                                                 .build())
                              .newDecorator();
    ServerBuilder sb = Server.builder()
                             .service("/message", myService.decorate(corsService));

Applying a policy to the specific paths
---------------------------------------
To configure a policy to the specific paths, you can use ``route()`` methods in the
:api:`CorsServiceBuilder` and :api:`ChainedCorsPolicyBuilder`. They can help you to adjust the scope that
a policy is applied to, e.g.

.. code-block:: java

    HttpService myService = (ctx, req) -> ...;
    Function<Service<HttpRequest, HttpResponse>, CorsService> corsService =
            CorsServiceBuilder.forOrigins("http://example.com")
                              // CORS policy will be applied for the path that starts with '/message/web/api/'.
                              .route("prefix:/message/web/api/")
                              .allowRequestMethods(HttpMethod.POST, HttpMethod.GET)
                              .newDecorator();
    ServerBuilder sb = Server.builder()
                             .service("/message", myService.decorate(corsService));

.. note::

    Please refer to the :api:`Route` in order to learn how to specify a path pattern.

Configuring CORS via annotation
-------------------------------

You can also configure CORS for :ref:`server-annotated-service` using the :api:`@CorsDecorator` annotation, e.g.

.. code-block:: java

    import com.linecorp.armeria.server.annotation.AdditionalHeader;
    import com.linecorp.armeria.server.annotation.Get;
    import com.linecorp.armeria.server.annotation.decorator.CorsDecorator;

    Object annotatedService = new Object() {
        @Get("/get")
        @CorsDecorator(origins = "http://example.com", credentialsAllowed = true,
                       nullOriginAllowed = true, exposedHeaders = "expose_header",
                       allowedRequestMethods = HttpMethod.GET, allowedRequestHeaders = "allow_header",
                       preflightResponseHeaders = {
                           @AdditionalHeader(name = "preflight_header", value = "preflight_value")
                       })
        // In case you want to configure different CORS policies for different origins.
        @CorsDecorator(origins = "http://example2.com", credentialsAllowed = true)
        public HttpResponse get() {
            return HttpResponse.of(HttpStatus.OK);
        }

        @Post("/post")
        // In case you want to allow any origin (*):
        @CorsDecorator(origins = "*", exposedHeaders = "expose_header")
        // You can not add a policy after adding the decorator allowing any origin (*).
        public HttpResponse post() {
            return HttpResponse.of(HttpStatus.OK)
        }
    };

    Server s = Server.builder()
                     .annotatedService("/example", annotatedService)
                     .build();

You can also use :api:`@CorsDecorator` at the class level to apply the decorator to all service methods in the class.
Note that the :api:`@CorsDecorator` annotation specified at the method level takes precedence over what's specified at the class level:

.. code-block:: java

    // This decorator will be ignored for the path "/post".
    @CorsDecorator(origins = "http://example.com", credentialsAllowed = true)
    class MyAnnotatedService {
        @Get("/get")
        public HttpResponse get() {
            return HttpResponse.of(HttpStatus.OK);
        }

        @Post("/post")
        @CorsDecorator(origins = "http://example2.com", credentialsAllowed = true)
        public HttpResponse post() {
            return HttpResponse.of(HttpStatus.OK);
        }
    }

If you want to allow a CORS policy to the specific paths, you can use ``pathPatterns`` property:

.. code-block:: java

    // This policy will be applied only to the paths matched by the pattern.
    @CorsDecorator(origins = "http://example.com", pathPatterns = "glob:/**/web/api", credentialsAllowed = true)
    class MyAnnotatedService {
        ...
    }
