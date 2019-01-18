.. _CORSWiki: https://en.wikipedia.org/wiki/Cross-origin_resource_sharing
.. _server-cors-service:

Configure CORS
========================

Armeria provides a way to configure Cross-origin resource sharing(CORS) policy for specified origins or
any origin via :api:`CorsServiceBuilder`. For more information about CORS, visit CORSWiki_.


Allowing any origin
-------------------
To configure CORS Service allowing any origin (*), use ``CorsServiceBuilder.forAnyOrigin()``

.. code-block:: java

    HttpService myService = new AbstractHttpService() {
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




Allowing the specified origins
------------------------------
To configure CORS Service allowing the specified origins, use ``CorsServiceBuilder.forOrigins`` or
``CorsServiceBuilder.forOrigin``, e.g.

.. code-block:: java

    HttpService myService = new AbstractHttpService() {
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


Applying a different policy for different origins
-------------------------------------------------
To configure multiple policies for different groups of origins, use ``andForOrigins`` or ``andForOrigin`` method
which creates a new instance of :api:`ChainedCorsPolicyBuilder` with the specified origins and
you have to call ``and()`` to return to :api:`CorsServiceBuilder` before calling ``newDecorator()``, e.g.

.. code-block:: java

    HttpService myService = new AbstractHttpService() {
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

    HttpService myService = new AbstractHttpService() {
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

