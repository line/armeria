.. _decorator: client-decorator.html
.. _RetryingClient: apidocs/index.html?com/linecorp/armeria/client/retry/RetryingClient.html
.. _RetryingHttpClient: apidocs/index.html?com/linecorp/armeria/client/retry/RetryingHttpClient.html
.. _RetryingRpcClient: apidocs/index.html?com/linecorp/armeria/client/retry/RetryingRpcClient.html
.. _ClientBuilder: apidocs/index.html?com/linecorp/armeria/client/ClientBuilder.html
.. _RetryStrategy: apidocs/index.html?com/linecorp/armeria/client/retry/RetryStrategy.html
.. _Backoff: apidocs/index.html?com/linecorp/armeria/client/retry/Backoff.html
.. _com.linecorp.armeria.client.retry: apidocs/index.html?com/linecorp/armeria/client/retry/package-summary.html
.. _LoggingClient: apidocs/index.html?com/linecorp/armeria/client/logging/LoggingClient.html
.. _ResponseTimeoutException: apidocs/index.html?com/linecorp/armeria/client/ResponseTimeoutException.html

.. _advanced-retry:

Automatic retry
===============

When a client gets an error response, it might want to retry the request depending on the response.
This can be accomplished using a decorator_, and Armeria provides the following implementations out-of-the box.

- RetryingHttpClient_
- RetryingRpcClient_

Both behave the same except for the different request and response types.
So, let's find out what we can do with RetryingClient_.

``RetryingClient``
------------------

You can just use the ``decorator()`` method in ClientBuilder_ to build a RetryingHttpClient_:

.. code-block:: java

    import com.linecorp.armeria.client.ClientBuilder;
    import com.linecorp.armeria.client.HttpClient;
    import com.linecorp.armeria.common.HttpRequest;
    import com.linecorp.armeria.common.HttpResponse;
    import com.linecorp.armeria.client.retry.RetryingHttpClient;
    import com.linecorp.armeria.client.retry.RetryStrategy;

    RetryStrategy strategy = RetryStrategy.onServerErrorStatus();
    HttpClient client = new ClientBuilder(...)
                              .decorator(HttpRequest.class, HttpResponse.class,
                                         RetryingHttpClient.newDecorator(strategy))
                              .build(HttpClient.class);

    client.execute(...).aggregate().join();

or even simply,

.. code-block:: java

    import com.linecorp.armeria.client.HttpClientBuilder;

    RetryStrategy strategy = RetryStrategy.onServerErrorStatus();
    HttpClient client = new HttpClientBuilder(...)
                              .decorator(RetryingHttpClient.newDecorator(strategy))
                              .build();

    client.execute(...).aggregate().join();

That's it. The client will keep attempting until it succeeds or the number of attempts exceeds the maximum
number of total attempts. You can configure the ``maxTotalAttempts`` when making the decorator using
``RetryingHttpClient.newDecorator(strategy, maxTotalAttempts)``. Meanwhile, the ``strategy`` will decide to
retry depending on the response. In this case, the client retries when it receives ``5xx`` response error.

.. _retry-strategy:

``RetryStrategy``
-----------------

You can customize the ``strategy`` by implementing RetryStrategy_.

.. code-block:: java

    import com.linecorp.armeria.client.retry.Backoff;
    import com.linecorp.armeria.common.HttpStatus;

    new RetryStrategy<HttpRequest, HttpResponse>() {
        final Backoff backoff = RetryStrategy.defaultBackoff;

        @Override
        public CompletableFuture<Optional<Backoff>> shouldRetry(HttpRequest request,
                                                                HttpResponse response) {
            return response.aggregate().handle((result, cause) -> { // Do not use get() or join()!
                if (cause != null) {
                    if (cause instanceof ResponseTimeoutException) {
                        return Optional.of(backoff);
                    }
                } else if (result.headers().status() == HttpStatus.CONFLICT) {
                    return Optional.of(backoff);
                }
                return Optional.empty(); // Return no backoff to stop retrying.
            });
        }
    };

This will retry when the response's status is ``409`` or ResponseTimeoutException_ is raised.

.. note::

    We declare a Backoff_ as a member and reuse it when a ``strategy`` returns it, so that we do not return
    a different Backoff_ instance for each ``shouldRetry()``. RetryingClient_ internally tracks the
    reference of the returned Backoff_ and increases the counter that keeps the number of attempts made so far,
    and resets it to 0 when the Backoff_ returned by the strategy is not the same as before. Therefore, it is
    important to return the same Backoff_ instance unless you decided to change your Backoff_ strategy. If you
    do not return the same one, when the Backoff_ yields a different delay based on the number of retries,
    such as an exponential backoff, it will not work as expected. We will take a close look into a Backoff_
    at the next section.

You can return a different Backoff_ according to the response.

.. code-block:: java

    import com.linecorp.armeria.client.ResponseTimeoutException;
    import com.linecorp.armeria.common.HttpStatusClass;

    new RetryStrategy<HttpRequest, HttpResponse>() {
        final Backoff backoffOnServerErrorOrTimeout = RetryStrategy.defaultBackoff;
        final Backoff backoffOnConflict = Backoff.fixed(100);

        @Override
        public CompletableFuture<Optional<Backoff>> shouldRetry(HttpRequest request,
                                                                HttpResponse response) {
            return response.aggregate().handle((result, cause) -> {
                if (cause != null) {
                    if (cause instanceof ResponseTimeoutException) {
                        return Optional.of(backoffOnServerErrorOrTimeout);
                    }
                } else if (result.headers().status().codeClass() == HttpStatusClass.SERVER_ERROR) {
                    return Optional.of(backoffOnServerErrorOrTimeout);
                } else if (result.headers().status() == HttpStatus.CONFLICT) {
                    return Optional.of(backoffOnConflict);
                }
                return Optional.empty();
            });
        }
    };

``Backoff``
-----------

You can use a Backoff_ to determine the delay between attempts. Armeria provides Backoff_ implementations which
produce the following delays out of the box:

- Fixed delay, created with ``Backoff.fixed()``
- Random delay, created with ``Backoff.random()``
- Exponential delay which is multiplied on each attempt, created with ``Backoff.exponential()``

Armeria provides ``RetryStrategy.defaultBackoff`` that you might use by default. It is exactly the same as:

.. code-block:: java

    Backoff.exponential(minDelayMillis /* 200 */, maxDelayMillis /* 10000 */, multiplier /* 2.0 */)
           .withJitter(jitterRate /* 0.2 */);

The delay starts from ``minDelayMillis`` until it reaches ``maxDelayMillis`` multiplying by multiplier every
retry. Please note that the ``.withJitter()`` will add jitter value to the calculated delay.

For more information, please refer to the API documentation of the `com.linecorp.armeria.client.retry`_ package.

``maxTotalAttempts`` vs per-Backoff ``maxAttempts``
---------------------------------------------------

If you create a Backoff_ using ``.withMaxAttempts(maxAttempts)`` in a RetryStrategy_, the RetryingClient_
which uses the RetryStrategy_ will stop retrying when the number of attempts passed ``maxAttempts``.
However, if you have more than one Backoff_ and return one after the other continuously, it will keep retrying
over and over again because the counter that RetryingClient_ internally tracks is initialized every time the
different Backoff_ is returned. To limit the number of attempts in a whole retry session, RetryingClient_ limits
the maximum number of total attempts to 10 by default. You can change this value by specifying
``maxTotalAttempts`` when you build a RetryingClient_:

.. code-block:: java

    RetryingHttpClient.newDecorator(strategy, maxTotalAttempts);

Or, you can override the default value of 10 using the JVM system property
``-Dcom.linecorp.armeria.defaultMaxTotalAttempts=<integer>``.

Per-attempt timeout
-------------------

ResponseTimeoutException_ can occur in two different situations while retrying. First, it occurs when the
time of whole retry session has passed the time previously configured using:

.. code-block:: java

    ClientBuilder.defaultResponseTimeoutMillis(millis);

    // or..
    ClientRequestContext.setResponseTimeoutMillis(millis);

You cannot retry on this ResponseTimeoutException_.
Second, it occurs when the time of individual attempt in retry has passed the time which is per-attempt timeout.
You can configure it when you create the decorator:

.. code-block:: java

    RetryingHttpClient.newDecorator(strategy, maxTotalAttempts, responseTimeoutMillisForEachAttempt);

You can retry on this ResponseTimeoutException_.

For example, when making a retrying request to an unresponsive service
with responseTimeoutMillis = 10,000, responseTimeoutMillisForEachAttempt = 3,000 and disabled Backoff_, the
first three attempts will be timed out by the per-attempt timeout (3,000ms). The 4th one will be aborted
after 1,000ms since the request session has reached at 10,000ms before it is timed out by the per-attempt
timeout.

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



.. _retry-with-logging:

RetryingClient with logging
---------------------------

You can use RetryingClient_ with LoggingClient_ to log. If you want to log all of the requests and responses,
decorate LoggingClient_ with RetryingClient_. That is:

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
    LoggingClient - Request: {startTime=..., length=..., duration=..., scheme=..., host=..., headers=[...]
    LoggingClient - Response: {startTime=..., length=..., duration=..., headers=[:status=500, ...]
    LoggingClient - Request: {startTime=..., length=..., duration=..., scheme=..., host=..., headers=[...]
    LoggingClient - Response: {startTime=..., length=..., duration=..., headers=[:status=200, ...]

If you want to log the first request and the last response, no matter if it's successful or not,
do the reverse:

.. code-block:: java

    import com.linecorp.armeria.client.logging.LoggingClient;

    RetryStrategy strategy = RetryStrategy.onServerErrorStatus();
    HttpClient client = new HttpClientBuilder(...)
                              .decorator(RetryingHttpClient.newDecorator(strategy))
      /* notice the order */  .decorator(LoggingClient.newDecorator())
                              .build();

This will produce only single request and response log pair regardless how many attempts are made:

.. code-block:: java

    LoggingClient - Request: {startTime=..., length=..., duration=..., scheme=..., host=..., headers=[...]
    LoggingClient - Response: {startTime=..., length=..., duration=..., headers=[:status=200, ...]

.. note::

    Please refer to :ref:`nested-log`, if you are curious about how this works internally.

See also
--------

- :ref:`advanced-structured-logging`
