.. _advanced-dropwizard-integration:

Using Armeria with Dropwizard
=================================

.. note::

    Visit `armeria examples <https://github.com/line/armeria/examples/dropwizard-bundle>`_ to find a fully working example.

Dropwizard provides many features which are necessary for building a web application, such as
metrics, model validation, externalized and extensible configuration, etc. In addition, if your Dropwizard application integrates
with Armeria, you can leverage the following:

- Rich support for Apache `Thrift <https://thrift.apache.org/>`_ and `gRPC <https://grpc.io/>`_,
  including :ref:`the fancy web console <server-docservice>` that enables you to send Thrift and gRPC requests
  from a web browser
- Ability to run HTTP REST service and RPC service in the same port
- Full HTTP/2 support for both server-side and client-side, including ``h2c`` (plaintext HTTP/2)
- `PROXY protocol <https://www.haproxy.org/download/1.8/doc/proxy-protocol.txt>`_ support which provides
  interoperability with load balancers such as `HAProxy <https://www.haproxy.org/>`_ and
  `AWS ELB <https://aws.amazon.com/elasticloadbalancing/>`_

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

The above dependencies import a new ``ServerFactory`` for Dropwizard to run on by referring to ``armeria`` type
server in the Dropwizard configuration file. A user can customize the server configuration with the same properties
provided by Dropwizard's ``SimpleServerFactory``.
The following is a simple example for configuring the server:

.. code-block:: yml

    server:
      type: armeria
      connector:
        type: armeria-http
        port: 8080

For a user who wants to customize the web server, :api:`ArmeriaBundle` is provided.
The user can customize the server by adding this bundle to Dropwizard as follows:

.. code-block:: java

    public class DropwizardArmeriaApplication extends Application<DropwizardArmeriaConfiguration> {

        public static void main(final String[] args) throws Exception {
            new DropwizardArmeriaApplication().run(args);
        }

        @Override
        public void initialize(final Bootstrap<DropwizardArmeriaConfiguration> bootstrap) {
            final ArmeriaBundle bundle = new ArmeriaBundle<DropwizardArmeriaConfiguration>() {
                @Override
                public void onServerBuilderReady(final ServerBuilder builder) {
                    // Customize the server using the given ServerBuilder. For example:
                    builder.service("/armeria", (ctx, res) -> HttpResponse.of("Hello, Armeria!"));

                    builder.annotatedService(new HelloService());

                    // You can also bind asynchronous RPC services such as Thrift and gRPC:
                    // builder.service(THttpService.of(...));
                    // builder.service(GrpcService.builder()...build());

                    // Add DocService that enables you to send Thrift and gRPC requests from web browser.
                    builder.serviceUnder("/docs", new DocService());
                }
            };
            bootstrap.addBundle(bundle);
        }
    }

.. note::

    If you are not familiar with Dropwizard, please refer to
    `Dropwizard Getting Started Guide <http://dropwizard.io/en/stable/getting-started.html>`_ and
    `Dropwizard User Manual <http://dropwizard.io/en/stable/manual/index.html>`_.
