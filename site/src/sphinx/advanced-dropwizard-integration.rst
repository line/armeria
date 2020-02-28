.. _advanced-dropwizard-integration:

Using Armeria with Dropwizard
=============================

.. note::

    Visit `armeria examples <https://github.com/line/armeria/tree/master/examples/dropwizard>`_ to find a fully working example.

Dropwizard provides many features which are necessary for building a web application, such as
metrics, model validation, externalized and extensible configuration, etc. In addition, if your Dropwizard application integrates
with Armeria, you can leverage the following:

- Rich support for Apache `Thrift <https://thrift.apache.org/>`_ and `gRPC <https://grpc.io/>`_,
  including :ref:`the fancy web console <server-docservice>` that enables you to send Thrift and gRPC requests
  from a web browser
- Ability to run HTTP REST service and RPC service in the same port
- Full HTTP/2 support for both server-side and client-side, including ``h2c`` (plaintext HTTP/2)

Armeria can be plugged in as the underlying HTTP server for a Dropwizard application by adding
the following dependency:

For Maven:

.. parsed-literal::
    :class: highlight-xml

    <dependencyManagement>
      <dependencies>
        <dependency>
          <groupId>com.linecorp.armeria</groupId>
          <artifactId>armeria-bom</artifactId>
          <version>\ |release|\ </version>
          <type>pom</type>
          <scope>import</scope>
        </dependency>
        <dependency>
          <groupId>io.dropwizard</groupId>
          <artifactId>dropwizard-bom</artifactId>
          <version>\ |io.dropwizard:dropwizard-core:version|\ </version>
          <type>pom</type>
          <scope>import</scope>
        </dependency>
      </dependencies>
    </dependencyManagement>

    <dependency>
        <groupId>com.linecorp.armeria</groupId>
        <artifactId>armeria-dropwizard</artifactId>
    </dependency>

For Gradle:

.. parsed-literal::
    :class: highlight-gradle

    dependencyManagement {
        imports {
            mavenBom 'com.linecorp.armeria:armeria-bom:\ |release|\ '
            mavenBom 'io.dropwizard:dropwizard-bom:\ |io.dropwizard:dropwizard-core:version|\ '
        }
    }

    dependencies {
        compile 'com.linecorp.armeria:armeria-dropwizard'
    }

The above dependencies import a new ``ServerFactory`` for Dropwizard to run on by referring to the ``armeria`` type
server in the Dropwizard configuration file. A user can customize the server configuration with `the same properties
provided by Dropwizard's <https://www.dropwizard.io/en/stable/manual/configuration.html#simple>`_ ``SimpleServerFactory``.
The following is a simple example for configuring the server:

.. code-block:: yml

    server:
      type: armeria
      applicationContextPath: /

For a user who wants to utilize Armeria, an :api:`ArmeriaBundle` implementation must be added to the
``Application``.

The user can further customize the server outside of the ``Configuration`` as follows:

.. code-block:: java

    public class DropwizardArmeriaApplication extends Application<DropwizardArmeriaConfiguration> {

        public static void main(final String[] args) throws Exception {
            new DropwizardArmeriaApplication().run(args);
        }

        @Override
        public void initialize(final Bootstrap<DropwizardArmeriaConfiguration> bootstrap) {
            final ArmeriaBundle bundle = new ArmeriaBundle() {
                @Override
                public void configure(final ServerBuilder builder) {
                    // Customize the server using the given ServerBuilder. For example:
                    builder.service("/armeria", (ctx, res) -> HttpResponse.of("Hello, Armeria!"));

                    builder.annotatedService(new HelloService());

                    // You can also bind asynchronous RPC services such as Thrift and gRPC:
                    // builder.service(THttpService.of(...));
                    // builder.service(GrpcService.builder()...build());
                }
            };
            bootstrap.addBundle(bundle);
        }
    }

.. note::

    If you are not familiar with Dropwizard, please refer to
    `Dropwizard Getting Started Guide <http://dropwizard.io/en/stable/getting-started.html>`_ and
    `Dropwizard User Manual <http://dropwizard.io/en/stable/manual/index.html>`_.

Server Properties
-----------------

.. note::

    Not all Dropwizard configurations can be passed into the Armeria server.  Currently supported parameters are:

.. code-block:: yml

    server:
      type: armeria
      gracefulShutdownQuietPeriodMillis: 5000
      gracefulShutdownTimeoutMillis: 40000
      maxRequestLength: 10485760
      maxNumConnections: 2147483647
      dateHeaderEnabled: true
      serverHeaderEnabled: false
      verboseResponses: false
      defaultHostname: "host.name.com"
      ports:
        - port: 8080
          protocol: HTTP
        - ip: 127.0.0.1
          port: 8081
          protocol: HTTPS
        - port: 8443
          protocols:
            - HTTPS
            - PROXY
              ports:
      compression:
        enabled: true
        mimeTypes:
          - text/*
          - application/json
        excludedUserAgents:
          - some-user-agent
          - another-user-agent
        minResponseSize: 1KB
      ssl:
        keyAlias: "host.name.com"
        keyStore: "classpath:keystore.jks"
        keyStorePassword: "changeme"
        trustStore: "classpath:truststore.jks"
        trustStorePassword: "changeme"
      http1:
        maxChunkSize: 4096
        maxInitialLineLength: 4096
      http2:
        initialConnectionWindowSize: 1MB
        initialStreamWindowSize: 1MB
        maxFrameSize: 16384
        maxHeaderListSize: 8192
      proxy:
        maxTlvSize: 65319
      accessLog:
        type: common

Where defined, the Armeria ServerFactory will prefer Armeria's default properties over Dropwizard's.
The following additional properties are able to be added to configure the :api:`ServerBuilder` before being
passed to the :api:`ArmeriaBundle`.

+------------------------+---------------------------------------+-------------------------------------------------+
| Path                   | Property                              | Description                                     |
+========================+=======================================+=================================================+
| ``server``             | ``jerseyEnabled``                     | Whether to enable JAX-RS resources defined by   |
|                        |                                       | Dropwizard (default: ``true``)                  |
+------------------------+---------------------------------------+-------------------------------------------------+
| ``server``             | ``maxRequestLength``                  | The default server-side maximum length of       |
|                        |                                       | a request                                       |
+------------------------+---------------------------------------+-------------------------------------------------+
| ``server``             | ``maxNumConnections``                 | The maximum allowed number of open connections  |
+------------------------+---------------------------------------+-------------------------------------------------+
| ``server``             | ``dateHeaderEnabled``                 | Whether to include default ``"Data"`` header    |
|                        |                                       | in the response header (default: ``true``)      |
+------------------------+---------------------------------------+-------------------------------------------------+
| ``server``             | ``serverHeaderEnabled``               | Whether to include default ``"Server"`` header  |
|                        |                                       | in the response header (default: ``false``)     |
+------------------------+---------------------------------------+-------------------------------------------------+
| ``server``             | ``verboseResponses``                  | Whether the verbose response mode is enabled    |
|                        |                                       | (default: ``false``)                            |
+------------------------+---------------------------------------+-------------------------------------------------+
| ``server``             | ``defaultHostname``                   | The default hostname of the default             |
|                        |                                       | :api:`VirtualHostBuilder`                       |
+------------------------+---------------------------------------+-------------------------------------------------+
| ``server``             | ``gracefulShutdownQuietPeriodMillis`` | The number of milliseconds to wait after the    |
|                        |                                       | last processed request to be considered safe    |
|                        |                                       | for shutdown                                    |
+------------------------+---------------------------------------+-------------------------------------------------+
| ``server``             | ``gracefulShutdownTimeoutMillis``     | The number of milliseconds to wait after going  |
|                        |                                       | unhealthy before forcing the server to shutdown |
|                        |                                       | regardless of if it is still processing requests|
+------------------------+---------------------------------------+-------------------------------------------------+
| ``server.ports``       | ``port``                              | The port to run the server on (default: 8080)   |
+                        +---------------------------------------+-------------------------------------------------+
|                        | ``ip``                                | The IP address to bind to                       |
+                        +---------------------------------------+-------------------------------------------------+
|                        | ``iface``                             | The network interface to bind to                |
+------------------------+---------------------------------------+-------------------------------------------------+
| ``server.compression`` | ``enabled``                           | Whether to enable the HTTP content encoding     |
|                        +---------------------------------------+-------------------------------------------------+
|                        | ``mimeTypes``                         | The MIME Types of an HTTP response which are    |
|                        |                                       | applicable for the HTTP content encoding        |
|                        +---------------------------------------+-------------------------------------------------+
|                        | ``excludedUserAgents``                | The ``"User-Agent"`` header values which are not|
|                        |                                       | applicable for the HTTP content encoding        |
|                        +---------------------------------------+-------------------------------------------------+
|                        | ``minResponseSize``                   | The minimum bytes for encoding the content of   |
|                        |                                       | an HTTP response                                |
+------------------------+---------------------------------------+-------------------------------------------------+
| ``server.ssl``         | ``enabled``                           | Whether to enable SSL support                   |
|                        +---------------------------------------+-------------------------------------------------+
|                        | ``keyAlias``                          | The alias that identifies the key in            |
|                        |                                       | the key store                                   |
|                        +---------------------------------------+-------------------------------------------------+
|                        | ``keyStore``                          | The path to the key store that holds the SSL    |
|                        |                                       | certificate (typically a jks file)              |
|                        +---------------------------------------+-------------------------------------------------+
|                        | ``keyStorePassword``                  | The password used to access the key store       |
|                        +---------------------------------------+-------------------------------------------------+
|                        | ``trustStore``                        | The trust store that holds SSL certificates     |
|                        +---------------------------------------+-------------------------------------------------+
|                        | ``trustStorePassword``                | The password used to access the trust store     |
+------------------------+---------------------------------------+-------------------------------------------------+
| ``server.http1``       | ``maxChunkSize``                      | The maximum length of each chunk in an HTTP/1   |
|                        |                                       | response content                                |
|                        +---------------------------------------+-------------------------------------------------+
|                        | ``maxInitialLineLength``              | The maximum length of an HTTP/1 response        |
|                        |                                       | initial line                                    |
+------------------------+---------------------------------------+-------------------------------------------------+
| ``server.http2``       | ``initialConnectionWindowSize``       | The initial connection-level HTTP/2 flow control|
|                        |                                       | window size                                     |
|                        +---------------------------------------+-------------------------------------------------+
|                        | ``initialStreamingWindowSize``        | The initial stream-level HTTP/2 flow control    |
|                        |                                       | window size                                     |
|                        +---------------------------------------+-------------------------------------------------+
|                        | ``maxFrameSize``                      | The maximum size of HTTP/2 frame that can be    |
|                        |                                       | received                                        |
|                        +---------------------------------------+-------------------------------------------------+
|                        | ``maxStreamsPerConnection``           | The maximum number of concurrent streams per    |
|                        |                                       | HTTP/2 connection. Unset means there is no limit|
|                        |                                       | on the number of concurrent streams             |
|                        +---------------------------------------+-------------------------------------------------+
|                        | ``maxHeaderListSize``                 | The maximum size of headers that can be received|
+------------------------+---------------------------------------+-------------------------------------------------+
| ``server.accessLog``   | ``type``                              | The access log type that is supposed to be one  |
|                        |                                       | of ``"common"``, ``"combined"`` or ``"custom"`` |
|                        +---------------------------------------+-------------------------------------------------+
|                        | ``format``                            | The access log format string                    |
+------------------------+---------------------------------------+-------------------------------------------------+

Server Access Logs
------------------
Armeria Server `Access Logging <server-access-log>` is enabled by default when using the Armeria Server.
The default :api:`AccessLogWriter` is ``AccessLogWriter.common()``, but this can be changed via the follow
configuration.

``common``
^^^^^^^^^^
Use NCSA common log format.

.. code-block:: yml

    server:
      type: armeria
    armeria:
      accessLog:
        type: common

``combined``
^^^^^^^^^^^^
Use NCSA combined log format.

.. code-block:: yml

    server:
      type: armeria
      accessLog:
        type: combined

``custom``
^^^^^^^^^^
Use your own log format. Refer to :ref:`customizing-log-format` for supported format tokens.

.. code-block:: yml

    server:
      type: armeria
      accessLog:
        type: custom
        format: "...log format..."
