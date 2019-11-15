.. _advanced-dropwizard-integration:

Using Armeria with Dropwizard
=================================

.. note::

    Visit `armeria examples <https://github.com/line/armeria/examples/dropwizard>`_ to find a fully working example.

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

    - ``maxThreads``
    - ``maxRequestLength``
    - ``idleThreadTimeout``
    - ``shutdownGracePeriod``

Where defined, the Armeria ServerFactory will prefer Armeria's default properties over Dropwizard's.
The following additional properties are able to be added to configure the ``ServerBuilder`` before being
passed to the :api:`ArmeriaBundle`.

+-----------------------------+-----------------------------------------------------------------------------+
| Property                    | Description                                                                 |
+=============================+=============================================================================+
| ``connector``               | the connector type  (default ``armeria-http``)                              |
+-----------------------------+-----------------------------------------------------------------------------+
| ``accessLogWriter``         | the access log writer  (default ``common``)                                 |
+-----------------------------+-----------------------------------------------------------------------------+
| ``jerseyEnabled``           | whether to enable JAX-RS resources defined by Dropwizard (default ``true``) |
+-----------------------------+-----------------------------------------------------------------------------+
| ``maxNumConnections``       | the maximum allowed number of open connections                              |
+-----------------------------+-----------------------------------------------------------------------------+
| ``dateHeaderEnabled``       | sets the response header to include default ``"Date"`` header               |
+-----------------------------+-----------------------------------------------------------------------------+
| ``verboseResponses``        | sets the response header not to include default ``"Server"`` header         |
+-----------------------------+-----------------------------------------------------------------------------+
| ``defaultHostname``         | sets the default hostname of the default :api:`VirtualHostBuilder`          |
+-----------------------------+-----------------------------------------------------------------------------+

Server Access Logs
------------------
Armeria Server `Access Logging <server-access-log>` is enabled by default when using the Armeria Server.
The default :api:`AccessLogWriter` is ``AccessLogWriter.common()``, but this can be changed via the follow
configuration.

``common``
^^^^^^^^^^^^^^^^
Use NCSA common log format.

.. code-block:: yml

    server:
      type: armeria
      accessLogWriter:
        type: common

``combined``
^^^^^^^^^^^^^^^^
Use NCSA combined log format.

.. code-block:: yml

    server:
      type: armeria
      accessLogWriter:
        type: combined

``custom``
^^^^^^^^^^^^^^^^
Use your own log format. Refer to :ref:`customize-log-format` for supported format tokens.

.. code-block:: yml

    server:
      type: armeria
      accessLogWriter:
        type: custom
        format: "...log format..."

Server Connectors
-----------------
Although Armeria itself does support server multiple protocols over the same port, this bundle currently only
supports one protocol per Server connector. Same as standard Dropwizard, these are configured with the
``connector`` type.

All connectors share the following properties:

+-----------------------------+----------------------------------------------------------------------+
| Property                    | Description                                                          |
+=============================+======================================================================+
| ``port``                    | the port to run the server on  (default 8080)                        |
+-----------------------------+----------------------------------------------------------------------+

``armeria-http``
^^^^^^^^^^^^^^^^

.. code-block:: yml

    server:
      type: armeria
      connector:
        type: armeria-http

Additional properties

+-----------------------------+----------------------------------------------------------------------+
| Property                    | Description                                                          |
+=============================+======================================================================+
| ``maxChunkSize``            | the maximum length of each chunk in an HTTP/1 response content.      |
|                             | The content or a chunk longer than this value will be split into     |
|                             | smaller chunks so that their lengths never exceed it.                |
+-----------------------------+----------------------------------------------------------------------+
| ``maxInitialLineLength``    | the maximum length of an HTTP/1 response initial line                |
+-----------------------------+----------------------------------------------------------------------+
| ``maxResponseHeaderSize``   | the maximum length of all headers in an HTTP/1 response              |
+-----------------------------+----------------------------------------------------------------------+

``armeria-https``
^^^^^^^^^^^^^^^^^

.. code-block:: yml

    server:
      type: armeria
      connector:
        type: armeria-https
        keyStorePath: /some/path/keystore.jks
        keyStorePassword: changeme

Additional properties

+-----------------------------------+----------------------------------------------------------------------+
| Property                          | Description                                                          |
+===================================+======================================================================+
| ``keyCertChainFile``              | a certificate chain file                                             |
+-----------------------------------+----------------------------------------------------------------------+
| ``selfSigned``                    | if the certificate is self-signed                                    |
+-----------------------------------+----------------------------------------------------------------------+
| ``initialConnectionWindowSize``   | the initial connection-level HTTP/2 flow control window size         |
+----------------------------------------------------------------------------------------------------------+
| ``initialStreamingWindowSize``    | the initial stream-level HTTP/2 flow control window size             |
+-----------------------------------+----------------------------------------------------------------------+
| ``maxFrameSize``                  | the maximum size of HTTP/2 frame that can be received                |
+-----------------------------------+----------------------------------------------------------------------+
| ``maxStreamsPerConnection``       | the maximum number of concurrent streams per HTTP/2 connection.      |
|                                   | Unset means there is no limit on the number of concurrent streams    |
+-----------------------------------+----------------------------------------------------------------------+
| ``maxHeaderListSize``             | the maximum size of headers that can be received                     |
+-----------------------------------+----------------------------------------------------------------------+
