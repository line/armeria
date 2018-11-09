.. _`Spring Boot Reference Guide`: https://docs.spring.io/spring-boot/docs/current/reference/html/
.. _`armeria-examples`: https://github.com/line/armeria-examples

.. _advanced-spring-webflux-integration:

Using Armeria as a web server of a reactive Spring application
==============================================================

A user can use Armeria as a web server of his or her reactive Spring application by adding
`spring-boot-webflux-starter` to the dependencies.

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

It automatically configures the Armeria web server by referring to `application.yml` when the application starts up.
A user can configure the server with the properties provided by Spring framework, because it replaces
the existing web server, such as Tomcat or Netty, with Armeria web server. The following is
a simple example for configuring the server:

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

Armeria-based `WebClient` is also provided to support the client-side, and :api:`ArmeriaClientConfigurator`
is provided as well in order to customize the Armeria HTTP client. A user can define a bean of the type
in the configuration as follows:

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
    Also, please refer to `Spring Boot Reference Guide`_ for more information about Spring Boot.
