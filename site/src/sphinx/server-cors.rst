.. _server-cors-service:

Configure CORS
========================

Armeria provides a way to configure Cross-origin resource sharing (CORS) policy for specified origins or
any origin via :api:`CorsServiceBuilder`. For more information about CORS,
Visit the Wikipedia's CORS <https://en.wikipedia.org/wiki/Cross-origin_resource_sharing>.


Allowing any origin
-------------------
To configure CORS Service allowing any origin (*), use ``CorsServiceBuilder.forAnyOrigin()``

.. code-block:: java

    HttpService myService = (ctx, req) -> {
                ...
    }
    ServerBuilder sb = new ServerBuilder().service("/message", myService.decorate(
            CorsServiceBuilder.forAnyOrigin()
                              .allowCredential()
                              .allowRequestMethods(HttpMethod.POST, HttpMethod.GET)
                              .allowRequestHeaders(HttpHeaderNames.of("allow_request_header"))
                              .exposeHeaders(HttpHeaderNames.of("expose_header_1"),
                                             HttpHeaderNames.of("expose_header_2"))
                              .preflightResponseHeader("x-preflight-cors", "Hello CORS")
                              .newDecorator()));




Allowing specified origins
------------------------------
To configure CORS Service allowing specified origins, use ``CorsServiceBuilder.forOrigins()`` or
``CorsServiceBuilder.forOrigin()``, e.g.

.. code-block:: java

    HttpService myService = (ctx, req) -> {
                ...
    }
    ServerBuilder sb = new ServerBuilder().service("/message", myService.decorate(
            CorsServiceBuilder.forOrigins("http://example.com")
                              .allowCredential()
                              .allowNullOrigin() // this property will allow "null" origin
                              .allowRequestMethods(HttpMethod.POST, HttpMethod.GET)
                              .allowRequestHeaders(HttpHeaderNames.of("allow_request_header"))
                              .exposeHeaders(HttpHeaderNames.of("expose_header_1"),
                                             HttpHeaderNames.of("expose_header_2"))
                              .preflightResponseHeader("x-preflight-cors", "Hello CORS")
                              .newDecorator()));


Applying different policies for different origins
-------------------------------------------------
To configure different policies for different groups of origins, use ``andForOrigins()`` or ``andForOrigin()``
method which creates a new :api:`ChainedCorsPolicyBuilder` with the specified origins.
Call ``and()`` to return to :api:`CorsServiceBuilder` once you are done with building a policy, e.g.

.. code-block:: java

    HttpService myService = (ctx, req) -> {
                ...
    }
    ServerBuilder sb = new ServerBuilder().service("/message", myService.decorate(
            CorsServiceBuilder.forOrigins("http://example.com")
                              .allowCredential()
                              .allowNullOrigin() // this property will allow "null" origin
                              .allowRequestMethods(HttpMethod.POST, HttpMethod.GET)
                              .allowRequestHeaders("allow_request_header")
                              .exposeHeaders("expose_header_1", "expose_header_2")
                              .preflightResponseHeader("x-preflight-cors", "Hello CORS")
                              .maxAge(3600)
                              .andForOrigins("http://example2.com")
                              .allowCredential()
                              .allowRequestMethods(HttpMethod.GET)
                              .allowRequestHeaders("allow_request_header2")
                              .exposeHeaders("expose_header_3", "expose_header_4")
                              .and()
                              .newDecorator()));

You can also directly add a :api:`CorsPolicy` created by a :api:`CorsPolicyBuilder`, e.g.

.. code-block:: java

    HttpService myService = (ctx, req) -> {
                ...
    }
    ServerBuilder sb = new ServerBuilder().service("/message", myService.decorate(
            CorsServiceBuilder.forOrigins("http://example.com")
                              .allowCredential()
                              .allowNullOrigin() // this property will allow "null" origin
                              .allowRequestMethods(HttpMethod.POST, HttpMethod.GET)
                              .allowRequestHeaders("allow_request_header")
                              .exposeHeaders("expose_header_1", "expose_header_2")
                              .preflightResponseHeader("x-preflight-cors", "Hello CORS")
                              .maxAge(3600)
                              .and()
                              .addPolicy(new CorsPolicyBuilder("http://example2.com")
                                            .allowCredential()
                                            .allowRequestMethods(HttpMethod.GET)
                                            .allowRequestHeaders("allow_request_header2")
                                            .exposeHeaders("expose_header_3", "expose_header_4")
                                            .build())
                              .newDecorator()));

