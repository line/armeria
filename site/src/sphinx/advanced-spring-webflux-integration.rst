.. _`Spring Boot Reference Guide`: https://docs.spring.io/spring-boot/docs/current/reference/html/
.. _`armeria-examples`: https://github.com/line/armeria-examples

.. _advanced-spring-webflux-integration:

Using Armeria with Spring WebFlux
=================================

Armeria can be plugged in as the underlying HTTP server for a Spring WebFlux application by adding
the following dependency:

For Maven:

.. code-block:: xml

    <dependency>
        <groupId>com.linecorp.armeria</groupId>
        <artifactId>spring-boot-webflux-starter</artifactId>
        <version>x.y.z</version>
    </dependency>

For Gradle:

.. code-block:: groovy

    dependencies {
        compile 'com.linecorp.armeria:spring-boot-webflux-starter:x.y.z'
    }

The above starter configures Armeria as the HTTP server for WebFlux to run on by referring to `application.yml`
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
            return builder -> {
                // Customize the server using the given ServerBuilder.
            };
        }
    }

Armeria can also be plugged as the underlying HTTP client for the Spring `WebClient`. To customize
client settings for the Armeria HTTP client, simply define an :api:`ArmeriaClientConfigurator` bean
in your configuration as follows:

.. code-block:: java

    @Configuration
    public class ArmeriaConfiguration {
        /**
         * A user can configure the client by providing an ArmeriaClientConfigurator bean.
         */
        @Bean
        public ArmeriaClientConfigurator armeriaClientConfigurator() {
            return builder -> {
                // Customize the client using the given HttpClientBuilder.
            }
        }
    }

.. note::

    You can find a simple reactive Spring Boot application from `armeria-examples`_.
    Also, please refer to `Spring Boot Reference Guide`_ for more information about Spring Boot and the
    `Spring Framework Reference Guide` for more information about Spring WebFlux.
