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

When a client gets error response, the client might want to retry the request depending on the response.
You can do this using :ref:`client-decorator` or use RetryingClient_ which already provides the
retrying functionality in Armeria.

There are two retrying clients:

- RetryingHttpClient_
- RetryingRpcClient_

They are same except that they have the different request and response types.
So, let's find out what we can do with RetryingClient_.

RetryingClient_
---------------

You can just use ``decorator`` method in ClientBuilder_ to build a RetryingHttpClient_:

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

That is it. The client will keep attempting until it succeeds or the number of attempts exceeds the default
number of max attempts. You can configure the ``defaultMaxAttempts`` when making the decorator using
``RetryingHttpClient.newDecorator(strategy, defaultMaxAttempts)``. Meanwhile, the ``strategy`` will decide to
retry depending on the response. In this case, the client retries when it receives ``5xx`` response error.

RetryStrategy
-------------

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
                return Optional.empty(); // Return no backoff not to retry anymore
            });
        }
    };

This will retry when the response's status is ``409`` or ResponseTimeoutException_ is raised.

.. note::

    We declare a Backoff_ as a member and reuse it when a ``strategy`` returns it, so that we do not return
    a different Backoff_ instance for each ``shouldRetry()``. RetryingClient_ internally tracks the
    reference of the returned Backoff_ and increases the counter that keeps the number of attempts made so far,
    and resets it to 0 when the Backoff_ returned by the strategy is not same as before. Therefore, it is
    important to return the same Backoff_ instance unless you decided to change your Backoff_ strategy, which
    yields a different delay based on the number of retried, will not work as expected. We will take
    a close look what the Backoff_ is at the bottom of this page.

You can return different Backoff_ according to the response.

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


Retry on ResponseTimeoutException_
----------------------------------

ResponseTimeoutException_ can occur in two different situations while retrying. First, it can occur when the
time of whole retry session has passed the time previously configured using:

.. code-block:: java

    ClientBuilder.defaultResponseTimeoutMillis(millis);

You cannot retry on this ResponseTimeoutException_.
Second, it can occur when the time of individual attempt in retry has passed the time configured when
creaing the decorator with:

.. code-block:: java

    RetryingHttpClient.newDecorator(strategy, defaultMaxAttempts, responseTimeoutMillisForEachAttempt);

You can retry on this ResponseTimeoutException_.

Backoff_
--------

You can use a Backoff_ to determine the delay between attempts. Armeria provides Backoff_ implementations which
produce the following delays out of the box:

- Fixed delay, created with ``Backoff.fixed()``
- Random delay, created with ``Backoff.random()``
- Exponential delay which is multiplied on each attempt, created with ``Backoff.exponential()``

Armeria provides ``RetryStrategy.defaultBackoff`` that you might use as default. It is exactly same with:

.. code-block:: java

    Backoff.exponential(minDelayMillis, maxDelayMillis, multiplier).withJitter(jitterRate);

The delay starts from ``minDelayMillis`` until it reaches ``maxDelayMillis`` multiplying with multiplier.
Please note that the ``.withJitter()`` will add jitter value to the calculated delay.
For more information, please refer to the API documentation of the `com.linecorp.armeria.client.retry`_ package.

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

This will produce following logs when there's three attempts:

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

This is achieved using the feature of :ref:`nested-log`. For more information, please refer to
:ref:`nested-log`.
