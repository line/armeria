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

