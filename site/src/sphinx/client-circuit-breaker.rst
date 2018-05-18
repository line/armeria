.. _CircuitBreaker by Martin Fowler: https://martinfowler.com/bliki/CircuitBreaker.html
.. _LINE Engineering blog: https://engineering.linecorp.com/en/blog/detail/76
.. _decorator: client-decorator.html

.. _client-circuit-breaker:

Circuit breaker
===============

In Microservice Architecture, it's common that various services running on different machines are connected to
each other through remote calls. If one of the services unreachable somehow such as TCP hang, it will take long
to get a response from the service. The client who connects to that service will be suffer from that.
If there are many remote calls to that unresponsive service, it will get worse.
You can configure an Armeria client with a circuit breaker to prevent this circumstance. The circuit breaker
can automatically detect failures by continuously updating success and failure events. If the remote service
is unresponsive, it will immediately respond with an error and not make a remote calls.
Please refer to `CircuitBreaker by Martin Fowler`_ and `LINE Engineering blog`_ for more information
about circuit breaker.

State of ``CircuitBreaker``
---------------------------

A :api:`CircuitBreaker` can be one of three states which are ``CLOSED``, ``OPEN`` and ``HALF_OPEN``.

- ``CLOSED``

  - Initial state. All requests are treated normally.

- ``OPEN``

  - The state changes to ``OPEN`` once the number of failures divided by the total number of requests exceeds
    the failure rate. All requests are blocked off responding with :api:`FailFastException`.

- ``HALF_OPEN``.

  - After a certain amount of time in the ``OPEN`` state, it changes to the ``HALF_OPEN`` state which tries
    to find out if the service is available by sending one request.
    If the request succeeds, the state changes to ``CLOSED``. If it fails, the state changes to ``OPEN``.

Here is the description how it works:

.. uml::

    @startditaa(--no-separation)

                                       +----------------+
                                       |                |
                                       |      OPEN      |
                                       |                |<-------------------+
                                       +------------+---+      fail again    |
                                           ^        |                        |
                                           |        |                        |
                                           |        |                        |
                                           |        |                        |
     under threshold                       |        |                        |
         +---+                             |        |                        |
         |   |                             |        |                        |
         |   v                             |        |                        |
    +----+-----------+  exceed threshold   |        |     timeout         +--+-------------+
    |                +---------------------+        +-------------------->|                |
    |     CLOSED     |                                                    |    HALF_OPEN   |
    |                |<---------------------------------------------------+                |
    +----------------+            back to normal(request succeed)         +----------------+

    @endditaa

``CircuitBreakerClient``
------------------------

Armeria provides two different :api:`Client` implementations depending on the
:api:`Request` and :api:`Response` types:

- :api:`CircuitBreakerHttpClient`
- :api:`CircuitBreakerRpcClient`

Let's use :api:`CircuitBreakerHttpClient` to find out what we can do.
You can use the decorator_ method in :api:`ClientBuilder` to build a :api:`CircuitBreakerHttpClient`:

.. code-block:: java

    import com.linecorp.armeria.client.circuitbreaker.CircuitBreaker;
    import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerHttpClient;
    import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerStrategy;
    import com.linecorp.armeria.client.ClientBuilder;
    import com.linecorp.armeria.client.HttpClient;
    import com.linecorp.armeria.common.HttpRequest;
    import com.linecorp.armeria.common.HttpResponse;

    final CircuitBreakerStrategy<HttpResponse> strategy = CircuitBreakerStrategy.onServerErrorStatus();
    final CircuitBreaker circuitBreaker = CircuitBreaker.ofDefaultName();
    final HttpClient client = new ClientBuilder(...)
            .decorator(HttpRequest.class, HttpResponse.class,
                       CircuitBreakerHttpClient.newDecorator(circuitBreaker, strategy))
            .build(HttpClient.class);

    client.execute(...).aggregate().join(); // Send requests on and on.

Now, the :api:`Client` can track the number of success or failure events depending on the :apiplural:`Response`.
The :api:`CircuitBreaker` will be ``OPEN``, when the number of failures divided by the total number of
:apiplural:`Request` exceeds the failure rate. Then the :api:`Client` will immediately get
:api:`FailFastException` by the :api:`CircuitBreaker`.

.. _circuit-breaker-strategy:

``CircuitBreakerStrategy``
--------------------------

How does the :api:`CircuitBreaker` know whether a :api:`Response` is successful or not?
:api:`CircuitBreakerStrategy` does the job. In the above example, if a :api:`Response`'s status is ``5xx``
or an ``Exception`` is raised during the call, the count of failure is increased.
Of course, you can have your own ``strategy`` by implementing :api:`CircuitBreakerStrategy`.

.. code-block:: java

    import com.linecorp.armeria.common.HttpStatusClass;

    final CircuitBreakerStrategy<HttpResponse> myStrategy = new CircuitBreakerStrategy<HttpResponse>() {

        @Override
        public CompletableFuture<Boolean> shouldReportAsSuccess(HttpResponse response) {
            return response.aggregate().handle((res, cause) -> {
                if (cause != null) { // A failure if an Exception is raised.
                    return false;
                }

                final HttpStatus status = res.status();
                if (status != null) {
                    // A failure if the response is 5xx.
                    if (status.codeClass() == HttpStatusClass.SERVER_ERROR) {
                        return false;
                    }

                    // A success if the response is 2xx.
                    if (status.codeClass() == HttpStatusClass.SUCCESS) {
                        return true;
                    }
                }

                // Not a success nor a failure. Do not include this response.
                return null;
            });
        }
    };

If you want to treat a :api:`Response` as a success, return ``true``. And return ``false`` as a failure.
Note that :api:`CircuitBreakerStrategy` can return ``null`` as well. It won't be counted as a success nor
a failure.

``Grouping``
------------

In the very first example above, a single :api:`CircuitBreaker` was used to track the events. However,
using only one :api:`CircuitBreaker` is not recommend. There might be an API which needs heavy calculation
causing failures frequently. On the other hands, there can be another API which does not need resources
and simply respond. Having one :api:`CircuitBreaker` that tracks all the success and failure does not make
sense in this scenario. It's even worse if the :api:`Client` connects to the services on different machines.
When one of the remote services is down, your :api:`CircuitBreaker` will probably be ``OPEN`` state although
you can connect to other services.
Therefore, Armeria provides various ways that let users group the range of circuit breaker instances.

- Per Host: a single :api:`CircuitBreaker` is used for each remote host.

.. code-block:: java

    import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRpcClient;
    import com.linecorp.armeria.common.RpcResponse;

    // Create a CircuitBreaker with the key name
    final Function<String, CircuitBreaker> factory = key -> CircuitBreaker.of("my-cb-" + key);
    final CircuitBreakerStrategy<HttpResponse> httpStrategy = CircuitBreakerStrategy.onServerErrorStatus();
    final CircuitBreakerStrategy<RpcResponse> rpcStrategy =
            response -> response.completionFuture().handle((res, cause) -> cause == null);

    // Create CircuitBreakers per host (a.com, b.com ...)
    CircuitBreakerHttpClient.newPerHostDecorator(factory, httpStrategy);
    CircuitBreakerRpcClient.newPerHostDecorator(factory, rpcStrategy);
    // The names of the created CircuitBreaker: my-cb-a.com, my-cb-b.com, ...

- Per Method: a single :api:`CircuitBreaker` is used for each method.

.. code-block:: java

    // Create CircuitBreakers per method
    CircuitBreakerHttpClient.newPerHostDecorator(factory, httpStrategy);
    // The names of the created CircuitBreaker: my-cb-GET, my-cb-POST, ...

    CircuitBreakerRpcClient.newPerHostDecorator(factory, rpcStrategy);
    // The names of the created CircuitBreaker: my-cb-methodA, my-cb-methodB, ...

- Per Host and Method: a single :api:`CircuitBreaker` is used each method in each host.

.. code-block:: java

    // Create CircuitBreakers per host and method
    CircuitBreakerHttpClient.newPerHostAndMethodDecorator(factory, httpStrategy);
    // The names of the created CircuitBreaker: my-cb-a.com#GET,
    // my-cb-a.com#POST, my-cb-b.com#GET, my-cb-b.com#POST, ...

    CircuitBreakerRpcClient.newPerHostAndMethodDecorator(factory, rpcStrategy);
    // The names of the created CircuitBreaker: my-cb-a.com#methodA,
    // my-cb-a.com#methodB, my-cb-b.com#methodA, my-cb-b.com#methodB, ...

If you want none of the above groupings, you can group them whatever you want using
:api:`KeyedCircuitBreakerMapping` and :api:`KeyedCircuitBreakerMapping.KeySelector`.

.. code-block:: java

    import com.linecorp.armeria.client.circuitbreaker.KeyedCircuitBreakerMapping;
    import com.linecorp.armeria.client.circuitbreaker.KeyedCircuitBreakerMapping.KeySelector;

    final KeyedCircuitBreakerMapping<String> mapping =
            new KeyedCircuitBreakerMapping<>((ctx, req) -> ctx.path(), factory);
    // I want to create CircuitBreakers per path!

    CircuitBreakerHttpClient.newDecorator(mapping, httpStrategy);

``CircuitBreakerBuilder``
-------------------------

We have used static methods in :api:`CircuitBreaker` interface to create a :api:`CircuitBreaker`.
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

  - The listeners which are notified when a event is happened in a :api:`CircuitBreaker`. The events are
    ``stateChanged``, ``eventCountUpdated`` and ``requestRejected``. You can export metrics using ``listeners``:

.. code-block:: java

    import com.linecorp.armeria.client.circuitbreaker.MetricCollectingCircuitBreakerListener

    import io.micrometer.core.instrument.Metrics;

    final MetricCollectingCircuitBreakerListener listener =
            new MetricCollectingCircuitBreakerListener(Metrics.globalRegistry);
    final CircuitBreakerBuilder builder = new CircuitBreakerBuilder().listener(listener);
