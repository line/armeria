.. _What are idempotent and/or safe methods?: http://restcookbook.com/HTTP%20Methods/idempotency/
.. _decorator: client-decorator.html

.. _client-retry:

Automatic retry
===============

When a client gets an error response, it might want to retry the request depending on the response.
This can be accomplished using a decorator_, and Armeria provides the following implementations out-of-the box.

- :api:`RetryingHttpClient`
- :api:`RetryingRpcClient`

Both behave the same except for the different request and response types.
So, let's find out what we can do with :api:`RetryingClient`.

``RetryingClient``
------------------

You can just use the ``decorator()`` method in :api:`ClientBuilder` to build a :api:`RetryingHttpClient`:

.. code-block:: java

    import com.linecorp.armeria.client.ClientBuilder;
    import com.linecorp.armeria.client.HttpClient;
    import com.linecorp.armeria.client.retry.RetryingHttpClient;
    import com.linecorp.armeria.client.retry.RetryStrategy;
    import com.linecorp.armeria.common.AggregatedHttpMessage;
    import com.linecorp.armeria.common.HttpRequest;
    import com.linecorp.armeria.common.HttpResponse;

    RetryStrategy strategy = RetryStrategy.onServerErrorStatus();
    HttpClient client = new ClientBuilder(...)
            .decorator(RetryingHttpClient.newDecorator(strategy))
            .build(HttpClient.class);

    final AggregatedHttpMessage res = client.execute(...).aggregate().join();

or even simply,

.. code-block:: java

    import com.linecorp.armeria.client.HttpClientBuilder;

    RetryStrategy strategy = RetryStrategy.onServerErrorStatus();
    HttpClient client = new HttpClientBuilder(...)
            .decorator(RetryingHttpClient.newDecorator(strategy))
            .build();

    final AggregatedHttpMessage res = client.execute(...).aggregate().join();

That's it. The client will keep attempting until it succeeds or the number of attempts exceeds the maximum
number of total attempts. You can configure the ``maxTotalAttempts`` when making the decorator using
``RetryingHttpClient.newDecorator(strategy, maxTotalAttempts)``. Meanwhile, the ``strategy`` will decide to
retry depending on the response. In this case, the client retries when it receives ``5xx`` response error or
an exception is raised.

.. _retry-strategy:

``RetryStrategy``
-----------------

You can customize the ``strategy`` by implementing :api:`RetryStrategy`.

.. code-block:: java

    import com.linecorp.armeria.client.ClientRequestContext;
    import com.linecorp.armeria.client.ResponseTimeoutException;
    import com.linecorp.armeria.client.UnprocessedRequestException;
    import com.linecorp.armeria.client.retry.Backoff;
    import com.linecorp.armeria.common.HttpStatus;

    new RetryStrategy() {
        final Backoff backoff = Backoff.ofDefault();

        @Override
        public CompletionStage<Backoff> shouldRetry(ClientRequestContext ctx, @Nullable Throwable cause) {
            if (cause != null) {
                if (cause instanceof ResponseTimeoutException ||
                    cause instanceof UnprocessedRequestException) {
                    // The response timed out or the request has not been handled by the server.
                    return CompletableFuture.completedFuture(backoff);
                }
            }

            if (ctx.log().responseHeaders().status() == HttpStatus.CONFLICT) {
                return CompletableFuture.completedFuture(backoff);
            }

            return CompletableFuture.completedFuture(null); // Return null to stop retrying.
        }
    };

This will retry when one of :api:`ResponseTimeoutException` and :api:`UnprocessedRequestException` is raised or
the response's status is ``409 Conflict``.

.. note::

    We declare a :api:`Backoff` as a member and reuse it when a ``strategy`` returns it, so that we do not
    return a different :api:`Backoff` instance for each ``shouldRetry()``. :api:`RetryingClient`
    internally tracks the reference of the returned :api:`Backoff` and increases the counter that keeps
    the number of attempts made so far, and resets it to 0 when the :api:`Backoff` returned by the strategy
    is not the same as before. Therefore, it is important to return the same :api:`Backoff` instance unless
    you decided to change your :api:`Backoff` strategy. If you do not return the same one, when the
    :api:`Backoff` yields a different delay based on the number of retries, such as an exponential backoff,
    it will not work as expected. We will take a close look into a :api:`Backoff` at the next section.

.. note::

    :api:`UnprocessedRequestException` literally means that the request has not been processed by the server.
    Therefore, you can safely retry the request without worrying about the idempotency of the request.
    For more information about idempotency, please refer to `What are idempotent and/or safe methods?`_.

You can return a different :api:`Backoff` according to the response status.

.. code-block:: java

    import com.linecorp.armeria.common.HttpStatusClass;

    new RetryStrategy() {
        final Backoff backoffOnServerErrorOrTimeout = Backoff.ofDefault();
        final Backoff backoffOnConflict = Backoff.fixed(100);

        @Override
        public CompletionStage<Backoff> shouldRetry(ClientRequestContext ctx, @Nullable Throwable cause) {
            if (cause != null) {
                if (cause instanceof ResponseTimeoutException ||
                    cause instanceof UnprocessedRequestException) {
                    // The response timed out or the request has not been handled by the server.
                    return CompletableFuture.completedFuture(backoffOnServerErrorOrTimeout);
                }
            }

            HttpStatus status = ctx.log().responseHeaders().status();
            if (status.codeClass() == HttpStatusClass.SERVER_ERROR) {
                return CompletableFuture.completedFuture(backoffOnServerErrorOrTimeout);
            } else if (status == HttpStatus.CONFLICT) {
                return CompletableFuture.completedFuture(backoffOnConflict);
            }

            return CompletableFuture.completedFuture(null); // Return null to stop retrying.
        }
    };

If you need to determine whether you need to retry by looking into the response content, you should implement
:api:`RetryStrategyWithContent` and specify it when you create an :api:`HttpClient`
using :api:`RetryingHttpClientBuilder`:

.. code-block:: java

    import com.linecorp.armeria.client.retry.RetryingHttpClientBuilder;
    import com.linecorp.armeria.client.retry.RetryStrategyWithContent;

    final RetryStrategyWithContent<HttpResponse> strategy = new RetryStrategyWithContent<HttpResponse>() {
        final Backoff backoff = Backoff.ofDefault();

        @Override
        public CompletionStage<Backoff> shouldRetry(ClientRequestContext ctx, HttpResponse response) {
            return response.aggregate().handle((result, thrown) -> {
                if (thrown != null) {
                    if (thrown instanceof ResponseTimeoutException ||
                        thrown instanceof UnprocessedRequestException) {
                        // The response timed out or the request has not been handled by the server.
                        return backoff;
                    }
                } else if ("Should I retry?".equals(result.contentUtf8())) {
                    return backoff;
                }
                return null; // Return null to stop retrying.
            });
        }
    };

    final HttpClient client = new HttpClientBuilder(...)
            .decorator(new RetryingHttpClientBuilder(strategy).newDecorator()) // Specify the strategy.
            .build();

    final AggregatedHttpMessage res = client.execute(...).aggregate().join();

``Backoff``
-----------

You can use a :api:`Backoff` to determine the delay between attempts. Armeria provides :api:`Backoff`
implementations which produce the following delays out of the box:

- Fixed delay, created with ``Backoff.fixed()``
- Random delay, created with ``Backoff.random()``
- Exponential delay which is multiplied on each attempt, created with ``Backoff.exponential()``

Armeria provides ``Backoff.ofDefault()`` that you might use by default. It is exactly the same as:

.. code-block:: java

    Backoff.exponential(minDelayMillis /* 200 */, maxDelayMillis /* 10000 */, multiplier /* 2.0 */)
           .withJitter(jitterRate /* 0.2 */);

The delay starts from ``minDelayMillis`` until it reaches ``maxDelayMillis`` multiplying by multiplier every
retry. Please note that the ``.withJitter()`` will add jitter value to the calculated delay.

For more information, please refer to the API documentation of the :api:`com.linecorp.armeria.client.retry`
package.

``maxTotalAttempts`` vs per-Backoff ``maxAttempts``
---------------------------------------------------

If you create a :api:`Backoff` using ``.withMaxAttempts(maxAttempts)`` in a :api:`RetryStrategy`,
the :api:`RetryingClient` which uses the :api:`RetryStrategy` will stop retrying when the number of
attempts passed ``maxAttempts``. However, if you have more than one :api:`Backoff` and return one after
the other continuously, it will keep retrying over and over again because the counter that
:api:`RetryingClient` internally tracks is initialized every time the different :api:`Backoff` is
returned. To limit the number of attempts in a whole retry session, :api:`RetryingClient` limits
the maximum number of total attempts to 10 by default. You can change this value by specifying
``maxTotalAttempts`` when you build a :api:`RetryingClient`:

.. code-block:: java

    RetryingHttpClient.newDecorator(strategy, maxTotalAttempts);

Or, you can override the default value of 10 using the JVM system property
``-Dcom.linecorp.armeria.defaultMaxTotalAttempts=<integer>``.

Note that when a :api:`RetryingClient` stops due to the attempts limit, the client will get the last received
:api:`Response` from the server.

Per-attempt timeout
-------------------

:api:`ResponseTimeoutException` can occur in two different situations while retrying. First, it occurs
when the time of whole retry session has passed the time previously configured using:

.. code-block:: java

    ClientBuilder.defaultResponseTimeoutMillis(millis);

    // or..
    ClientRequestContext.setResponseTimeoutMillis(millis);

You cannot retry on this :api:`ResponseTimeoutException`.
Second, it occurs when the time of individual attempt in retry has passed the time which is per-attempt timeout.
You can configure it when you create the decorator:

.. code-block:: java

    RetryingHttpClient.newDecorator(strategy, maxTotalAttempts, responseTimeoutMillisForEachAttempt);

You can retry on this :api:`ResponseTimeoutException`.

For example, when making a retrying request to an unresponsive service
with ``responseTimeoutMillis = 10,000``, ``responseTimeoutMillisForEachAttempt = 3,000`` and disabled
:api:`Backoff`, the first three attempts will be timed out by the per-attempt timeout (3,000ms).
The 4th one will be aborted after 1,000ms since the request session has reached at 10,000ms before
it is timed out by the per-attempt timeout.

.. uml::

    @startditaa(--no-separation, --no-shadows, scale=0.95)
    0ms         3,000ms     6,000ms     9,000ms
    |           |           |           |
    +-----------+-----------+-----------+----+
    | Attempt 1 | Attempt 2 | Attempt 3 | A4 |
    +-----------+-----------+-----------+----+
                                             |
                                           10,000ms (ResponseTimeoutException)
    @endditaa

In the example above, every attempt is made before it is timed out because the :api:`Backoff` is disabled.
However, what if a :api:`Backoff` is enabled and the moment of trying next attempt is after the point of
:api:`ResponseTimeoutException`? In such a case, the :api:`RetryingClient` does not schedule for the
next attempt, but finishes the retry session immediately with the last received :api:`Response`.
Consider the following example:

.. uml::

    @startditaa(--no-separation, --no-shadows, scale=0.95)
    0ms         3,000ms     6,000ms     9,000ms     12,000ms
    |           |           |           |           |
    +-----------+-----------+-----------+-----------+-----------------------+
    | Attempt 1 |           | Attempt 2 |           | Attempt 3 is not made |
    +-----------+-----------+-----------+----+------+-----------------------+
                                        |    |
                                        | 10,000ms (retry session deadline)
                                        |
                                    stops retrying at this point
    @endditaa

Unlike the example above, the :api:`Backoff` is enabled and it makes the :api:`RetryingClient` perform retries
with 3-second delay. When the second attempt is finished at 9,000ms, the next attempt will be at 12,000ms
exceeding the response timeout of 10,000ms.
The :api:`RetryingClient`, at this point, stops retrying and finished the retry session with the last received
:api:`Response`, retrieved at 9,000ms from the attempt 2.

.. _retry-with-logging:

``RetryingClient`` with logging
-------------------------------

You can use :api:`RetryingClient` with :api:`LoggingClient` to log. If you want to log all of the
requests and responses, decorate :api:`LoggingClient` with :api:`RetryingClient`. That is:

.. code-block:: java

    RetryStrategy strategy = RetryStrategy.onServerErrorStatus();
    HttpClient client = new HttpClientBuilder(...)
            .decorator(LoggingClient.newDecorator())
            .decorator(RetryingHttpClient.newDecorator(strategy))
            .build();

This will produce following logs when there are three attempts:

.. code-block:: java

    LoggingClient - Request: {startTime=..., length=..., duration=..., scheme=..., host=..., headers=[...]
    LoggingClient - Response: {startTime=..., length=..., duration=..., headers=[:status=500, ...]
    LoggingClient - Request: {startTime=..., ..., headers=[..., armeria-retry-count=1, ...]
    LoggingClient - Response: {startTime=..., length=..., duration=..., headers=[:status=500, ...]
    LoggingClient - Request: {startTime=..., ..., headers=[..., armeria-retry-count=2, ...]
    LoggingClient - Response: {startTime=..., length=..., duration=..., headers=[:status=200, ...]

.. note::

    Did you notice that the ``armeria-retry-count`` header is inserted from the second request?
    :api:`RetryingClient` inserts it to indicate the retry count of a request.
    The server might use this value to reject excessive retries, etc.

If you want to log the first request and the last response, no matter if it's successful or not,
do the reverse:

.. code-block:: java

    import com.linecorp.armeria.client.logging.LoggingClient;

    RetryStrategy strategy = RetryStrategy.onServerErrorStatus();
    // Note the order of decoration.
    HttpClient client = new HttpClientBuilder(...)
            .decorator(RetryingHttpClient.newDecorator(strategy))
            .decorator(LoggingClient.newDecorator())
            .build();

This will produce single request and response log pair and the total number of attempts only, regardless
how many attempts are made:

.. code-block:: java

    LoggingClient - Request: {startTime=..., length=..., duration=..., scheme=..., host=..., headers=[...]
    LoggingClient - Response: {startTime=..., length=..., headers=[:status=200, ...]}, {totalAttempts=3}

.. note::

    Please refer to :ref:`nested-log`, if you are curious about how this works internally.

``RetryingClient`` with circuit breaker
---------------------------------------

You might want to use :ref:`client-circuit-breaker` with :api:`RetryingHttpClient` using decorator_:

.. code-block:: java

    import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerStrategy;
    import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerHttpClientBuilder;

    CircuitBreakerStrategy cbStrategy = CircuitBreakerStrategy.onServerErrorStatus();
    RetryStrategy myRetryStrategy = new RetryStrategy() { ... };

    HttpClient client = new HttpClientBuilder(...)
            .decorator(new CircuitBreakerHttpClientBuilder(cbStrategy).newDecorator())
            .decorator(new RetryingHttpClientBuilder(myRetryStrategy).newDecorator())
            .build();

    final AggregatedHttpMessage res = client.execute(...).aggregate().join();

This decorates :api:`CircuitBreakerHttpClient` with :api:`RetryingHttpClient` so that the :api:`CircuitBreaker`
judges every request and retried request as successful or failed. If the failure rate exceeds a certain
threshold, it raises a :api:`FailFastException`. When using both clients, you need to write a custom
:api:`RetryStrategy` to handle this exception so that the :api:`RetryingHttpClient` does not attempt
a retry unnecessarily when the circuit is open, e.g.

.. code-block:: java

    import com.linecorp.armeria.client.circuitbreaker.FailFastException;

    new RetryStrategy() {
        final Backoff backoff = Backoff.ofDefault();

        @Override
        public CompletionStage<Backoff> shouldRetry(ClientRequestContext ctx, @Nullable Throwable cause) {
            if (cause != null) {
                if (cause instanceof FailFastException) {
                    // The circuit is already open so returns null to stop retrying.
                    return CompletableFuture.completedFuture(null);
                }

                if (cause instanceof ResponseTimeoutException ||
                    cause instanceof UnprocessedRequestException) {
                    // The response timed out or the request has not been handled by the server.
                    return CompletableFuture.completedFuture(backoff);
                }
            }
            ... // Implement the rest of your own strategy.
        }
    };

.. note::

    You may want to allow retrying even on :api:`FailFastException` when your endpoint is configured with
    client-side load balancing because the next attempt might be sent to the next available endpoint.
    See :ref:`client-service-discovery` for more information about client-side load balancing.

See also
--------

- :ref:`advanced-structured-logging`
