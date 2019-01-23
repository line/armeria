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
    ServerBuilder sb = new ServerBuilder().service("/message", myService.decorate(
            CorsServiceBuilder.forAnyOrigin()
                              .allowCredentials()
                              .allowRequestMethods(HttpMethod.POST, HttpMethod.GET)
                              .allowRequestHeaders("allow_request_header")
                              .exposeHeaders("expose_header_1", "expose_header_2")
                              .preflightResponseHeader("x-preflight-cors", "Hello CORS")
                              .newDecorator()));

Allowing specific origins
-------------------------
To configure CORS Service allowing specific origins, use ``CorsServiceBuilder.forOrigins()`` or
``CorsServiceBuilder.forOrigin()``, e.g.

.. code-block:: java

    HttpService myService = (ctx, req) -> ...;
    ServerBuilder sb = new ServerBuilder().service("/message", myService.decorate(
            CorsServiceBuilder.forOrigins("http://example.com")
                              .allowCredentials()
                              .allowNullOrigin() // 'Origin: null' will be accepted.
                              .allowRequestMethods(HttpMethod.POST, HttpMethod.GET)
                              .allowRequestHeaders("allow_request_header")
                              .exposeHeaders("expose_header_1", "expose_header_2")
                              .preflightResponseHeader("x-preflight-cors", "Hello CORS")
                              .newDecorator()));


Applying different policies for different origins
-------------------------------------------------
To configure different policies for different groups of origins, use ``andForOrigins()`` or ``andForOrigin()``
method which creates a new :api:`ChainedCorsPolicyBuilder` with the specific origins.
Call ``and()`` to return to :api:`CorsServiceBuilder` once you are done with building a policy, e.g.

.. code-block:: java

    HttpService myService = (ctx, req) -> ...;
    ServerBuilder sb = new ServerBuilder().service("/message", myService.decorate(
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
                              .newDecorator()));

You can also directly add a :api:`CorsPolicy` created by a :api:`CorsPolicyBuilder`, e.g.

.. code-block:: java

    import com.linecorp.armeria.common.HttpMethod;
    import com.linecorp.armeria.server.HttpService;
    import com.linecorp.armeria.server.ServerBuilder;
    import com.linecorp.armeria.server.cors.CorsServiceBuilder;
    import com.linecorp.armeria.server.cors.CorsPolicyBuilder;

    HttpService myService = (ctx, req) -> ...;
    ServerBuilder sb = new ServerBuilder().service("/message", myService.decorate(
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
                              .newDecorator()));

Configuring CORS via annotation
-------------------------------

Armeria provides a way to configure CORS via :api:`@CorsDecorator`, e.g.

.. code-block:: java

    import com.linecorp.armeria.common.HttpMethod;
    import com.linecorp.armeria.common.HttpResponse;
    import com.linecorp.armeria.common.HttpStatus;
    import com.linecorp.armeria.server.HttpService;
    import com.linecorp.armeria.server.ServerBuilder;
    import com.linecorp.armeria.server.annotation.AdditionalHeader;
    import com.linecorp.armeria.server.annotation.Get;
    import com.linecorp.armeria.server.annotation.decorator.CorsDecorator;

    Server s = new ServerBuilder().annotatedService("/example", new Object() {
        @Get("/get")
        @CorsDecorator(origins = "http://example.com", credentialAllowed = true,
                        nullOriginAllowed = true, exposedHeaders = "expose_header",
                        allowedRequestMethods = HttpMethod.GET, allowedRequestHeaders = "allow_header",
                        preflightResponseHeaders = {
                            @AdditionalHeader(name = "preflight_header", value = "preflight_value")
                        })
        // In case you want to configure different CORS Policies for different origins.
        @CorsDecorator(origins = "http://example2.com", credentialAllowed = true)
        public HttpResponse get() {
            return HttpResponse.of(HttpStatus.OK);
        }

        @Post("/post")
        // In case you want to configure CORS Service which allows any origin (*).
        // You can not add a policy after adding the decorator.
        @CorsDecorator(origins = "*", exposedHeaders = "expose_header")
        public HttpResponse post() {
            return HttpResponse.of(HttpStatus.OK)
        }
    }).build();
    ...

You can also use :api:`@CorsDecorator` above class to apply the decorator to every service in the class.
Alternatively, Armeria ignores :api:`@CorsDecorator` set above class for the service which already has an :api:`@CorsDecorator`.

.. code-block:: java

    // this decorator will be ignored for the path "/post"
    @CorsDecorator(origins = "http://example.com", credentialAllowed = true)
    class MyAnnotatedService {
        @Get("/get")
        public HttpResponse get() {
            return HttpResponse.of(HttpStatus.OK);
        }

        @Post("/post")
        @CorsDecorator(origins = "http://example2.com", credentialAllowed = true)
        public HttpResponse post() {
            return HttpResponse.of(HttpStatus.OK);
        }
    }
    ...

    Server s = new ServerBuilder().annotatedService(new MyAnnotatedService()).build();
    ...