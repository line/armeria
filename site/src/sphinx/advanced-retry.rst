.. _RetryingClient: apidocs/index.html?com/linecorp/armeria/client/retry/RetryingClient.html
.. _RetryingHttpClient: apidocs/index.html?com/linecorp/armeria/client/retry/RetryingHttpClient.html
.. _RetryingRpcClient: apidocs/index.html?com/linecorp/armeria/client/retry/RetryingRpcClient.html
.. _ClientBuilder: apidocs/index.html?com/linecorp/armeria/client/ClientBuilder.html
.. _RetryStrategy: apidocs/index.html?com/linecorp/armeria/client/retry/RetryStrategy.html
.. _Backoff: apidocs/index.html?com/linecorp/armeria/client/retry/Backoff.html
.. _FixedBackoff: apidocs/index.html?com/linecorp/armeria/client/retry/Backoff.html
.. _RandomBackoff: apidocs/index.html?com/linecorp/armeria/client/retry/Backoff.html
.. _ExponentialBackoff: apidocs/index.html?com/linecorp/armeria/client/retry/Backoff.html
.. _com.linecorp.armeria.client.retry: apidocs/index.html?com/linecorp/armeria/client/retry/package-summary.html
.. _LoggingClient: apidocs/index.html?com/linecorp/armeria/client/logging/LoggingClient.html

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

    RetryStrategy strategy = RetryStrategy.onServerErrorStatus();
    HttpClient client = new ClientBuilder(...)
                              .decorator(HttpRequest.class, HttpResponse.class,
                                         RetryingHttpClient.newDecorator(strategy))
                              .build(HttpClient.class);

    client.execute(...).aggregate().join();

or even simply,

.. code-block:: java

    RetryStrategy strategy = RetryStrategy.onServerErrorStatus();
    HttpClient client = new HttpClientBuilder(...)
                          .decorator(RetryingHttpClient.newDecorator(strategy))
                          .build();

    client.execute(...).aggregate().join();

That is it. The client will keep attempting until it succeeds or the number of attempts exceeds the default
number of max attempts. Meanwhile, the ``strategy`` will decide to retry depending on the response.
In this case, the client retries when it receives ``5xx`` response error.

RetryStrategy
-------------

You can customize the ``strategy`` by implementing RetryStrategy_.

.. code-block:: java

    new RetryStrategy<HttpRequest, HttpResponse>() {
        final Backoff backoff = RetryStrategy.defaultBackoff;

        @Override
        public CompletableFuture<Optional<Backoff>> shouldRetry(HttpRequest request,
                                                                HttpResponse response) {
            return response.aggregate().handle((result, cause) -> { // don't do get() or join()!
                ...
                if (result.headers().status() == CONFLICT) {
                    return Optional.of(backoff);
                }
                return Optional.empty();
            });
        }
    };

This will retry when the response's status is ``409``.

.. note::

    We declare a ``backoff`` as a member and return it. The inside of RetryingClient_ tracks the
    reference of the ``backoff`` and increases the counter one by one. The counter will be used
    as the ``numAttemptsSoFar`` to get the next delay in Backoff_. we will take a close look what
    the Backoff_ is at the bottom of this page.

You can return different ``backoffs`` according to the response.

.. code-block:: java

    new RetryStrategy<HttpRequest, HttpResponse>() {
        final Backoff backoffOnServerError = RetryStrategy.defaultBackoff;
        final Backoff backoffOnConflict = Backoff.fixed(100);

        @Override
        public CompletableFuture<Optional<Backoff>> shouldRetry(HttpRequest request,
                                                                HttpResponse response) {
            return response.aggregate().handle((result, cause) -> {
                ...
                if (result.headers().status() == SERVER_ERROR) {
                    return Optional.of(backoffOnServerError);
                } else if (result.headers().status() == CONFLICT) {
                      return Optional.of(backoffOnConflict);
                }
                return Optional.empty();
            });
        }
    };

.. _backoff-section:

Backoff_
--------

You can use a ``backoff`` to determine the delay between attempts. There are three ``backoffs`` in Armeria:

- FixedBackoff_
- RandomBackoff_
- ExponentialBackoff_

``FixedBackoff`` returns the fixed milliseconds which is specified when it is created using
``Backoff.fixed(delay)``. ``RandomBackoff`` returns a random milliseconds between minimum and maximum delays.
``ExponentialBackoff`` returns a milliseconds which is exponentially multiplied in each attempt.
If you invoke ``withJitter(jitterRate)`` on the instance, the delay will have the jitter.
For more information, please refer to the API documentation of the `com.linecorp.armeria.client.retry`_ package.

RetryingClient with logging
---------------------------

You can use RetryingClient_ with LoggingClient_ to log. If you want to log the first request and the last
response(no matter if it's successful or not), decorate RetryingClient_ with LoggingClient_. That is:

.. code-block:: java

    RetryStrategy strategy = RetryStrategy.onServerErrorStatus();
    HttpClient client = new HttpClientBuilder(...)
                              .decorator(RetryingHttpClient.newDecorator(strategy))
                              .decorator(LoggingClient.newDecorator())
                              .build();

If you want to log all of the requests and responses, then do the reverse:

.. code-block:: java

    RetryStrategy strategy = RetryStrategy.onServerErrorStatus();
    HttpClient client = new HttpClientBuilder(...)
                              .decorator(LoggingClient.newDecorator())
      /* notice the order */  .decorator(RetryingHttpClient.newDecorator(strategy))
                              .build();
