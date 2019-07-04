.. _advanced-zipkin:

Zipkin integration
==================

If you want to troubleshoot latency problems in microservice architecture, you will want to use distributed
tracing system such as `Zipkin <https://zipkin.io/>`_. It gathers timing data and shows which component is
the problem. Armeria supports distributed tracing via `Brave <https://github.com/openzipkin/brave/>`_, which
is a Java tracing library compatible with `Zipkin <https://zipkin.io/>`_. Let's find out how to use it
to trace requests.

First, you need to create the :api:`Tracing`:

.. code-block:: java

    import com.linecorp.armeria.common.brave.RequestContextCurrentTraceContext;

    import brave.Tracing;

    Reporter<Span> myReporter = ...
    Tracing tracing = Tracing.newBuilder()
                             .localServiceName("myService")
                             .currentTraceContext(RequestContextCurrentTraceContext.ofDefault())
                             .spanReporter(myReporter)
                             .build();

Please note that we specified :api:`RequestContextCurrentTraceContext`. It stores the trace context into a
:api:`RequestContext` and loads the trace context from the :api:`RequestContext` automatically. Because of that,
we don't need to use a thread local variable which can lead to unpredictable behavior in asynchronous
programming. If you want to send timing data to the span collecting server, you should specify ``spanReporter``.
For more information about the ``spanReporter``, please refer to
`Zipkin reporter <https://github.com/openzipkin/zipkin-reporter-java>`_ or
`the fully working example <https://github.com/openzipkin-contrib/zipkin-armeria-example>`_.

Now, you can specify :api:`BraveService` using :ref:`server-decorator` with the :api:`Tracing` you just built:

.. code-block:: java

    import com.linecorp.armeria.common.HttpResponse;
    import com.linecorp.armeria.server.Server;
    import com.linecorp.armeria.server.ServerBuilder;
    import com.linecorp.armeria.server.brave.BraveService;

    Tracing tracing = ...
    Server server = new ServerBuilder().http(8081)
                                       .service("/", (ctx, res) -> HttpResponse.of(200))
                                       .decorator(BraveService.newDecorator(tracing))
                                       .build();

If the requests come to Armeria server and go to another backend, you can trace them using
:api:`BraveClient`:

.. code-block:: java

    import com.linecorp.armeria.client.BraveClient;
    import com.linecorp.armeria.client.HttpClient;
    import com.linecorp.armeria.client.HttpClientBuilder;
    import com.linecorp.armeria.server.brave.BraveService;

    Tracing tracing = ...
    HttpClient client = new HttpClientBuilder("https://myBackend.com")
            .decorator(BraveClient.newDecorator(tracing, "myBackend"))
            .build();

    Server server = new ServerBuilder().http(8081)
                                       .service("/", (ctx, res) -> client.get("/api"))
                                       .decorator(BraveService.newDecorator(tracing))
                                       .build();

Please note that we used the same :api:`Tracing` instance when we create :api:`BraveClient` and
:api:`BraveService`. Otherwise, there might be problems if the instances are not configured exactly the same.

See also
--------

- `Armeria Zipkin example <https://github.com/openzipkin-contrib/zipkin-armeria-example>`_