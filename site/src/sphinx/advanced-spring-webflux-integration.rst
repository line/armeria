.. _advanced-spring-webflux-integration:

Using Armeria with Spring WebFlux
=================================

.. note::

    Visit `armeria-examples <https://github.com/line/armeria-examples>`_ to find a fully working example.

Spring framework provides powerful features which are necessary for building a web application, such as
dependency injection, data binding, AOP, transaction, etc. In addition, if your Spring application integrates
with Armeria, you can leverage the following:

- Rich support for Apache `Thrift <https://thrift.apache.org/>`_ and `gRPC <https://grpc.io/>`_,
  including :ref:`the fancy web console <server-docservice>` that enables you to send Thrift and gRPC requests
  from a web browser
- Ability to run HTTP REST service and RPC service in the same port
- Full HTTP/2 support for both server-side and client-side, including ``h2c`` (plaintext HTTP/2)
- `PROXY protocol <https://www.haproxy.org/download/1.8/doc/proxy-protocol.txt>`_ support which provides
  interoperability with load balancers such as `HAProxy <https://www.haproxy.org/>`_ and
  `AWS ELB <https://aws.amazon.com/elasticloadbalancing/>`_

Armeria can be plugged in as the underlying HTTP server for a Spring WebFlux application by adding
the following dependency:

For Maven:

.. parsed-literal::
    :class: highlight-xml

    <dependency>
        <groupId>com.linecorp.armeria</groupId>
        <artifactId>armeria-spring-boot-webflux-starter</artifactId>
        <version>\ |release|\ </version>
    </dependency>

For Gradle:

.. parsed-literal::
    :class: highlight-groovy

    dependencies {
        compile 'com.linecorp.armeria:armeria-spring-boot-webflux-starter:\ |release|\ '
    }

The above starter configures Armeria as the HTTP server for WebFlux to run on by referring to ``application.yml``
when the application starts up. A user can customize the server configuration with the same properties
provided by Spring Boot as for other servers supported with WebFlux such as Tomcat or Reactor Netty.
The following is a simple example for configuring the server:

.. code-block:: yml

    server:
      address: 127.0.0.1
      port: 8080

For a user who wants to customize the web server, :api:`ArmeriaServerConfigurator` is provided.
The user can customize the server by defining a bean of the type in the configuration as follows:

.. code-block:: java

    @Configuration
    public class ArmeriaConfiguration {
        /**
         * A user can configure the server by providing an ArmeriaServerConfigurator bean.
         */
        @Bean
        public ArmeriaServerConfigurator armeriaServerConfigurator() {
            // Customize the server using the given ServerBuilder. For example:
            return builder -> {
                // Add DocService that enables you to send Thrift and gRPC requests from web browser.
                builder.serviceUnder("/docs", new DocService());

                // Log every message which the server receives and responds.
                builder.decorator(LoggingService.newDecorator());

                // Write access log after completing a request.
                builder.accessLogWriter(AccessLogWriter.combined(), false);

                // You can also bind asynchronous RPC services such as Thrift and gRPC:
                // builder.service(THttpService.of(...));
                // builder.service(new GrpcServiceBuilder()...build());
            };
        }
    }

Armeria can also be plugged as the underlying HTTP client for the Spring ``WebClient``. To customize
client settings for the Armeria HTTP client, simply define an :api:`ArmeriaClientConfigurator` bean
in your configuration as follows:

.. code-block:: java

    @Configuration
    public class ArmeriaConfiguration {
        /**
         * Returns a custom ClientFactory with TLS certificate validation disabled,
         * which means any certificate received from the server will be accepted without any verification.
         * It is used for an example which makes the client send an HTTPS request to the server running
         * on localhost with a self-signed certificate. Do NOT use the InsecureTrustManagerFactory
         * in production.
         */
        @Bean
        public ClientFactory clientFactory() {
            return new ClientFactoryBuilder().sslContextCustomizer(
                    b -> b.trustManager(InsecureTrustManagerFactory.INSTANCE)).build();
        }

        /**
         * A user can configure a Client by providing an ArmeriaClientConfigurator bean.
         */
        @Bean
        public ArmeriaClientConfigurator armeriaClientConfigurator(ClientFactory clientFactory) {
            // Customize the client using the given HttpClientBuilder. For example:
            return builder -> {
                // Use a circuit breaker for each remote host.
                final CircuitBreakerStrategy strategy = CircuitBreakerStrategy.onServerErrorStatus();
                builder.decorator(new CircuitBreakerHttpClientBuilder(strategy).newDecorator());

                // Set a custom client factory.
                builder.factory(clientFactory);
            };
        }
    }

.. note::

    If you are not familiar with Spring Boot and Spring WebFlux, please refer to
    `Spring Boot Reference Guide <https://docs.spring.io/spring-boot/docs/current/reference/html/>`_ and
    `Web on Reactive Stack <https://docs.spring.io/spring/docs/current/spring-framework-reference/web-reactive.html>`_.
