.. _CircuitBreaker wiki page: https://martinfowler.com/bliki/CircuitBreaker.html
.. _LINE Engineering blog post about circuit breaker: https://engineering.linecorp.com/en/blog/detail/76

.. _client-circuit-breaker:

Circuit breaker
===============

In microservice architecture, it's common that various services running on different machines are connected to
each other through remote calls. If one of the services becomes unreachable somehow such as due to network
issues, the client which connects to that service will take long to get a response from it or to fail to
get one. Situation will get even worse if there are many remote calls to such unresponsive service.
You can configure an Armeria client with a circuit breaker to prevent this circumstance. The circuit breaker
can automatically detect failures by continuously updating success and failure events. If the remote service
is unresponsive, it will immediately respond with an error and not make remote calls.
Please refer to `CircuitBreaker wiki page`_ by Martin Fowler and
`LINE Engineering blog post about circuit breaker`_ for more information.

State of ``CircuitBreaker``
---------------------------

A :api:`CircuitBreaker` can be one of the following three states:

- ``CLOSED``

  - Initial state. All requests are treated normally.

- ``OPEN``

  - The state machine enters the ``OPEN`` state once the number of failures divided by the total number of
    requests exceeds a certain threshold. All requests are blocked off responding with :api:`FailFastException`.

- ``HALF_OPEN``.

  - After a certain amount of time in the ``OPEN`` state, the state machine enters the ``HALF_OPEN`` state
    which sends a request to find out if the service is still unavailable or not.
    If the request succeeds, it enters the ``CLOSED`` state. If it fails, it enters the ``OPEN`` state again.

.. uml::

    @startditaa(--no-separation)

                                       +----------------+
                                       |                |
                                       |      OPEN      |
                                       |                |<-------------------+
                                       +------------+---+     failed again   |
                                           ^        |                        |
                                           |        |                        |
                                           |        |                        |
                                           |        |                        |
     under threshold                       |        |                        |
         +---+                             |        |                        |
         |   |                             |        |                        |
         |   v                             |        |                        |
    +----+-----------+  exceeded threshold |        |     timed out       +--+-------------+
    |                +---------------------+        +-------------------->|                |
    |     CLOSED     |                                                    |    HALF_OPEN   |
    |                |<---------------------------------------------------+                |
    +----------------+          back to normal (request succeeded)        +----------------+

    @endditaa

``CircuitBreakerClient``
------------------------

Armeria provides two different :api:`Client` implementations depending on the
:api:`Request` and :api:`Response` types:

- :api:`CircuitBreakerHttpClient`
- :api:`CircuitBreakerRpcClient`

Let's use :api:`CircuitBreakerHttpClient` to find out what we can do.
You can use the ``decorator()`` method in :api:`ClientBuilder` to build a :api:`CircuitBreakerHttpClient`:

.. code-block:: java

    import com.linecorp.armeria.client.circuitbreaker.CircuitBreaker;
    import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerHttpClient;
    import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerStrategy;
    import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerHttpClientBuilder;
    import com.linecorp.armeria.client.HttpClientBuilder;
    import com.linecorp.armeria.client.HttpClient;
    import com.linecorp.armeria.common.AggregatedHttpMessage;
    import com.linecorp.armeria.common.HttpRequest;
    import com.linecorp.armeria.common.HttpResponse;

    final CircuitBreakerStrategy strategy = CircuitBreakerStrategy.onServerErrorStatus();
    final HttpClient client = new HttpClientBuilder(...)
            .decorator(new CircuitBreakerHttpClientBuilder(strategy).newDecorator())
            .build();

    final AggregatedHttpMessage res = client.execute(...).aggregate().join(); // Send requests on and on.

Now, the :api:`Client` can track the number of success or failure events depending on the :apiplural:`Response`.
The :api:`CircuitBreaker` will enter ``OPEN``, when the number of failures divided by the total number of
:apiplural:`Request` exceeds the failure rate. Then the :api:`Client` will immediately get
:api:`FailFastException` by the :api:`CircuitBreaker`.

.. _circuit-breaker-strategy:

``CircuitBreakerStrategy``
--------------------------

How does a :api:`CircuitBreaker` know whether a :api:`Response` is successful or not?
:api:`CircuitBreakerStrategy` does the job. In the example above, if the status of a :api:`Response` is ``5xx``
or an ``Exception`` is raised during the call, the count of failure is increased.
Of course, you can have your own ``strategy`` by implementing :api:`CircuitBreakerStrategy`.
The following example shows a :api:`CircuitBreakerStrategy` implementation that fails when an ``Exception``
is raised or the status is ``5xx``, succeeds when the status is ``2xx`` and ignores others.

.. code-block:: java

    import com.linecorp.armeria.client.ClientRequestContext;
    import com.linecorp.armeria.client.UnprocessedRequestException;
    import com.linecorp.armeria.common.HttpStatus;
    import com.linecorp.armeria.common.HttpStatusClass;

    final CircuitBreakerStrategy myStrategy = new CircuitBreakerStrategy() {

        @Override
        public CompletionStage<Boolean> shouldReportAsSuccess(ClientRequestContext ctx,
                                                              @Nullable Throwable cause) {
            if (cause != null) {
                if (cause instanceof UnprocessedRequestException) {
                    // Neither a success nor a failure because the request has not been handled by the server.
                    return CompletableFuture.completedFuture(null);
                }
                // A failure if an Exception is raised.
                return CompletableFuture.completedFuture(false);
            }

            final HttpStatus status = ctx.log().responseHeaders().status();
            if (status != null) {
                // A failure if the response is 5xx.
                if (status.codeClass() == HttpStatusClass.SERVER_ERROR) {
                    return CompletableFuture.completedFuture(false);
                }

                // A success if the response is 2xx.
                if (status.codeClass() == HttpStatusClass.SUCCESS) {
                    return CompletableFuture.completedFuture(true);
                }
            }

            // Neither a success nor a failure. Do not take this response into account.
            return CompletableFuture.completedFuture(null);
        }
    };

If you want to treat a :api:`Response` as a success, return ``true``. Return ``false`` to treat as a failure.
Note that :api:`CircuitBreakerStrategy` can return ``null`` as well. It won't be counted as a success nor
a failure.

If you need to determine whether the request was successful by looking into the response content,
you should implement :api:`CircuitBreakerStrategyWithContent` and specify it when you create an
:api:`HttpClient` using :api:`CircuitBreakerHttpClientBuilder`:

.. code-block:: java

    import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerStrategyWithContent;

    final CircuitBreakerStrategyWithContent<HttpResponse> myStrategy =
            new CircuitBreakerStrategyWithContent<HttpResponse>() {

                @Override
                public CompletionStage<Boolean> shouldReportAsSuccess(ClientRequestContext ctx,
                                                                      HttpResponse response) {
                    return response.aggregate().handle((res, cause) -> {
                        if (cause != null) {
                            if (cause instanceof UnprocessedRequestException) {
                                // Neither a success nor a failure because the request has not been handled
                                // by the server.
                                return null;
                            }
                            // A failure if an Exception is raised.
                            return false;
                        }

                        final String content = res.contentUtf8();
                        if ("Success".equals(content)) {
                            return true;
                        } else if ("Failure".equals(content)) {
                            return false;
                        }

                        // Neither a success nor a failure. Do not take this response into account.
                        return null;
                    });
                }
            };

    final HttpClient client = new HttpClientBuilder(...)
            .decorator(new CircuitBreakerHttpClientBuilder(myStrategy).newDecorator()) // Specify the strategy
            .build();

    final AggregatedHttpMessage res = client.execute(...).aggregate().join();

Grouping ``CircuitBreaker``\s
-----------------------------

In the very first example above, a single :api:`CircuitBreaker` was used to track the events. However,
in many cases, you will want to use different :api:`CircuitBreaker` for different endpoints. For example, there
might be an API which performs heavy calculation which fails often. On the other hand, there can be another API
which is not resource hungry and this is not likely to fail.
Having one :api:`CircuitBreaker` that tracks all the success and failure does not make sense in this scenario.
It's even worse if the :api:`Client` connects to the services on different machines.
When one of the remote services is down, your :api:`CircuitBreaker` will probably be ``OPEN`` state although
you can connect to other services.
Therefore, Armeria provides various ways that let users group the range of circuit breaker instances.

- Group by host: a single :api:`CircuitBreaker` is used for each remote host.

    .. code-block:: java

        import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRpcClient;
        import com.linecorp.armeria.common.RpcResponse;

        // Create a CircuitBreaker with the key name
        final Function<String, CircuitBreaker> factory = key -> CircuitBreaker.of("my-cb-" + key);
        final CircuitBreakerStrategy httpStrategy = CircuitBreakerStrategy.onServerErrorStatus();
        final CircuitBreakerStrategy rpcStrategy =
                response -> response.completionFuture().handle((res, cause) -> cause == null);

        // Create CircuitBreakers per host (a.com, b.com ...)
        CircuitBreakerHttpClient.newPerHostDecorator(factory, httpStrategy);
        CircuitBreakerRpcClient.newPerHostDecorator(factory, rpcStrategy);
        // The names of the created CircuitBreaker: my-cb-a.com, my-cb-b.com, ...

- Group by method: a single :api:`CircuitBreaker` is used for each method.

    .. code-block:: java

        // Create CircuitBreakers per method
        CircuitBreakerHttpClient.newPerMethodDecorator(factory, httpStrategy);
        // The names of the created CircuitBreaker: my-cb-GET, my-cb-POST, ...

        CircuitBreakerRpcClient.newPerMethodDecorator(factory, rpcStrategy);
        // The names of the created CircuitBreaker: my-cb-methodA, my-cb-methodB, ...

- Group by host and method: a single :api:`CircuitBreaker` is used for each method and host combination.

    .. code-block:: java

        // Create CircuitBreakers per host and method
        CircuitBreakerHttpClient.newPerHostAndMethodDecorator(factory, httpStrategy);
        // The names of the created CircuitBreaker: my-cb-a.com#GET,
        // my-cb-a.com#POST, my-cb-b.com#GET, my-cb-b.com#POST, ...

        CircuitBreakerRpcClient.newPerHostAndMethodDecorator(factory, rpcStrategy);
        // The names of the created CircuitBreaker: my-cb-a.com#methodA,
        // my-cb-a.com#methodB, my-cb-b.com#methodA, my-cb-b.com#methodB, ...

If you want none of the above groupings, you can group them however you want using
:api:`KeyedCircuitBreakerMapping` and :api:`KeyedCircuitBreakerMapping$KeySelector`.

.. code-block:: java

    import com.linecorp.armeria.client.circuitbreaker.KeyedCircuitBreakerMapping;
    import com.linecorp.armeria.client.circuitbreaker.KeyedCircuitBreakerMapping.KeySelector;

    // I want to create CircuitBreakers per path!
    final KeyedCircuitBreakerMapping<String> mapping =
            new KeyedCircuitBreakerMapping<>((ctx, req) -> ctx.path(), factory);

    CircuitBreakerHttpClient.newDecorator(mapping, httpStrategy);

``CircuitBreakerBuilder``
-------------------------

We have used static factory methods in :api:`CircuitBreaker` interface to create a :api:`CircuitBreaker` so far.
If you use :api:`CircuitBreakerBuilder`, you can configure the parameters which decide
:api:`CircuitBreaker`'s behavior. Here are the parameters:

- ``name``:

  - The name of the :api:`CircuitBreaker`.

- ``failureRateThreshold``:

  - The threshold that changes :api:`CircuitBreaker`'s state to ``OPEN`` when the number of failed
    :apiplural:`Request` divided by the number of total :apiplural:`Request` exceeds it.
    The default value is ``0.2``.

- ``minimumRequestThreshold``:

  - The minimum number of :apiplural:`Request` to detect failures. The default value is ``10``.

- ``trialRequestInterval``:

  - The duration that a :api:`CircuitBreaker` remains in ``HALF_OPEN`` state. All requests are blocked off
    responding with :api:`FailFastException` during this state. The default value is ``3`` seconds.

- ``circuitOpenWindow``:

  - The duration that a :api:`CircuitBreaker` remains in ``OPEN`` state. All :apiplural:`Request` are blocked
    off responding with :api:`FailFastException` during this state. The default value is ``10`` seconds.

- ``counterSlidingWindow``:

  - The duration of a sliding window that a :api:`CircuitBreaker` counts successful and failed
    :apiplural:`Request` in it. The default value is ``20`` seconds.

- ``counterUpdateInterval``:

  - The duration that a :api:`CircuitBreaker` stores events in a bucket. The default value is ``1`` second.

- ``listeners``:

  - The listeners which are notified by a :api:`CircuitBreaker` when an event occurs. The events are one of
    ``stateChanged``, ``eventCountUpdated`` and ``requestRejected``. You can use
    :api:`MetricCollectingCircuitBreakerListener` to export metrics:

    .. code-block:: java

        import com.linecorp.armeria.client.circuitbreaker.MetricCollectingCircuitBreakerListener

        import io.micrometer.core.instrument.Metrics;

        final MetricCollectingCircuitBreakerListener listener =
                new MetricCollectingCircuitBreakerListener(Metrics.globalRegistry);
        final CircuitBreakerBuilder builder = new CircuitBreakerBuilder().listener(listener);

.. _circuit-breaker-with-non-armeria-client:

Using ``CircuitBreaker`` with non-Armeria client
------------------------------------------------

:api:`CircuitBreaker` API is designed to be framework-agnostic and thus can be used with any non-Armeria
clients:

1. Create a :api:`CircuitBreaker`.
2. Guard your client calls with ``CircuitBreaker.canRequest()``.
3. Update the state of :api:`CircuitBreaker` by calling ``CircuitBreaker.onSuccess()`` or ``onFailure()``
   depending on the outcome of the client call.

For example:

.. code-block:: java

    private final CircuitBreaker cb = CircuitBreaker.of("some-client");
    private final SomeClient client = ...;

    public void sendRequestWithCircuitBreaker() {
        if (!cb.canRequest()) {
            throw new RuntimeException();
        }

        boolean success = false;
        try {
            success = client.sendRequest();
        } finally {
            if (success) {
                cb.onSuccess();
            } else {
                cb.onFailure();
            }
        }
    }
