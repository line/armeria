.. _advanced-production-checklist:

Production checklist
====================

.. note::

    Note that the advices in this page are not always applicable for every use case and thus should be
    applied with caution. Do not apply the changes you really do not need.

You may want to consider the following options before putting your Armeria application into production.

- Specify the maximum number of accepted connections. The default is *unbounded*.

  .. code-block:: java

      import com.linecorp.armeria.server.ServerBuilder;

      ServerBuilder sb = new ServerBuilder();
      sb.maxNumConnections(500);

- Specify an alternative ``blockingTaskExecutor`` based on expected workload if your server has
  a :api:`Service` that uses it, such as :api:`TomcatService`, :api:`JettyService` and :api:`THttpService` with
  synchronous service implementation. The default is a simple ``ThreadPoolExecutor`` with 200 threads and an
  *unbounded* queue, provided by :api:`CommonPools`.

  .. code-block:: java

      import com.linecorp.armeria.server.ServerBuilder;

      ServerBuilder sb = new ServerBuilder();
      sb.blockingTaskExecutor(myBoundedExecutor);

- Specify the default limits of an HTTP request or response.

  .. code-block:: java

      import java.time.Duration;
      import com.linecorp.armeria.client.ClientBuilder;
      import com.linecorp.armeria.server.ServerBuilder;

      // Server-side
      ServerBuilder sb = new ServerBuilder();
      sb.defaultMaxRequestLength(1048576); // bytes (default: 10 MiB)
      sb.defaultRequestTimeout(Duration.ofSeconds(7)); // (default: 10 seconds)

      // Client-side
      ClientBuilder cb = new ClientBuilder(...); // or HttpClientBuilder
      cb.defaultMaxResponseLength(1048576); // bytes (default: 10 MiB)
      cb.defaultResponseTimeout(Duration.ofSeconds(10)); // (default: 15 seconds)

- Decorate your services with :api:`ThrottlingService` which lets you fail the incoming requests based on a
  policy, such as 'fail if the rate of requests exceed a certain threshold.'

  .. code-block:: java

      import com.linecorp.armeria.server.throttling.RateLimitingThrottlingStrategy;
      import com.linecorp.armeria.server.throttling.ThrottlingHttpService;

      ServerBuilder sb = new ServerBuilder();
      sb.service("/my_service", // Allow up to 1000 requests/sec.
                 myService.decorate(ThrottlingHttpService.newDecorator(
                         new RateLimitingThrottlingStrategy(1000.0))));

- Decorate your clients with :api:`RetryingClient`. See :ref:`client-retry`.
- Decorate your clients with :api:`CircuitBreakerClient`. See :ref:`client-circuit-breaker`.

  .. tip::

      You can use Armeria's :api:`CircuitBreaker` API for non-Armeria clients without circuit breaker support.
      See :ref:`circuit-breaker-with-non-armeria-client`.

- Tune the socket options.

  .. code-block:: java

      import com.linecorp.armeria.client.ClientBuilder;
      import com.linecorp.armeria.client.ClientFactory;
      import com.linecorp.armeria.client.ClientFactoryBuilder;
      import com.linecorp.armeria.server.ServerBuilder;
      import io.netty.channel.ChannelOption;

      // Server-side
      ServerBuilder sb = new ServerBuilder();
      sb.channelOption(ChannelOption.SO_BACKLOG, ...);
      sb.channelOption(ChannelOption.SO_SNDBUF, ...);
      sb.channelOption(ChannelOption.SO_RCVBUF, ...);

      // Client-side
      ClientFactoryBuilder cfb = new ClientFactoryBuilder();
      cfb.channelOption(ChannelOption.SO_REUSEADDR, ...);
      cfb.channelOption(ChannelOption.SO_SNDBUF, ...);
      cfb.channelOption(ChannelOption.SO_RCVBUF, ...);
      ClientFactory cf = cfb.build();
      ClientBuilder cb = new ClientBuilder(...);
      cb.factory(cf);
