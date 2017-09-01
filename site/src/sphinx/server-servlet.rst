.. _Apache Tomcat: https://tomcat.apache.org/
.. _Jetty: https://www.eclipse.org/jetty/
.. _JettyService: apidocs/index.html?com/linecorp/armeria/server/jetty/JettyService.html
.. _JettyServiceBuilder: apidocs/index.html?com/linecorp/armeria/server/jetty/JettyServiceBuilder.html
.. _ServerBuilder: apidocs/index.html?com/linecorp/armeria/server/ServerBuilder.html
.. _TomcatService: apidocs/index.html?com/linecorp/armeria/server/tomcat/TomcatService.html
.. _TomcatServiceBuilder: apidocs/index.html?com/linecorp/armeria/server/tomcat/TomcatServiceBuilder.html

.. _server-servlet:

Embedding a servlet container
=============================
You can make Armeria serve your JEE web application on the same JVM and TCP/IP port by embedding
`Apache Tomcat`_ or Jetty_. Neither Tomcat nor Jetty will open a server socket or accept an incoming
connection. All HTTP requests and responses go through Armeria. As a result, you get the following bonuses:

- Your webapp gets HTTP/2 support for free even if your servlet container does not support it.
- You can run your RPC services on the same JVM and port as your webapp with no performance loss.

Embedding Apache Tomcat
-----------------------

Add a TomcatService_ to a ServerBuilder_:

.. code-block:: java

    import com.linecorp.armeria.server.ServerBuilder;
    import com.linecorp.armeria.server.tomcat.TomcatService;

    ServerBuilder sb = new ServerBuilder();

    sb.serviceUnder("/tomcat/api/rest/v2/",
                    TomcatService.forCurrentClassPath("/webapp"));

    sb.serviceUnder("/tomcat/api/rest/v1/",
                    TomcatService.forFileSystem("/var/lib/webapps/old_api.war"));

For more information, please refer to the API documentation of TomcatService_ and TomcatServiceBuilder_.

Embedding Jetty
---------------

Unlike Apache Tomcat, you need more dependencies and bootstrap code due to its modular design:

- org.eclipse.jetty:jetty-webapp
- org.eclipse.jetty:jetty-annotations
- org.eclipse.jetty:apache-jsp
- org.eclipse.jetty:apache-jstl

.. code-block:: java

    import com.linecorp.armeria.server.ServerBuilder;
    import com.linecorp.armeria.server.jetty.JettyServiceBuilder;

    import org.eclipse.jetty.annotations.ServletContainerInitializersStarter;
    import org.eclipse.jetty.apache.jsp.JettyJasperInitializer
    import org.eclipse.jetty.plus.annotation.ContainerInitializer
    import org.eclipse.jetty.util.resource.Resource;
    import org.eclipse.jetty.webapp.WebAppContext;

    ServerBuilder sb = new ServerBuilder();

    sb.serviceUnder("/jetty/api/rest/v2/",
                    new JettyServiceBuilder().handler(newWebAppContext("/webapp"))
                                             .build());

    static WebAppContext newWebAppContext(String resourcePath) throws MalformedURLException {
        final WebAppContext handler = new WebAppContext();
        handler.setContextPath("/");
        handler.setBaseResource(Resource.newClassPathResource(resourcePath));
        handler.setClassLoader(/* Specify your class loader here. */);
        handler.addBean(new ServletContainerInitializersStarter(handler), true);
        handler.setAttribute(
                "org.eclipse.jetty.containerInitializers",
                Collections.singletonList(
                        new ContainerInitializer(new JettyJasperInitializer(), null)));
        return handler;
    }

For more information, please refer to the API documentation of JettyService_ and JettyServiceBuilder_.
