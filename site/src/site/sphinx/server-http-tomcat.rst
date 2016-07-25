.. _`TomcatService`: apidocs/index.html?com/linecorp/armeria/server/http/tomcat/TomcatService.html
.. _`TomcatServiceBuilder`: apidocs/index.html?com/linecorp/armeria/server/http/tomcat/TomcatServiceBuilder.html

Embedding Apache Tomcat
=======================
You can make Armeria serve your JEE web application on the same JVM and TCP/IP port by adding a
``TomcatService`` to a ``Server``:

.. code-block:: java

    ServerBuilder sb = new ServerBuilder();
    sb.serviceUnder("/api/rest/v2/",
                    TomcatService.forCurrentClassPath());

    sb.serviceUnder("/api/rest/v1/",
                    TomcatService.forFileSystem("/var/lib/webapps/old_api.war"));

Note that Tomcat will not open a server socket or accept an incoming connection. All HTTP requests and
responses go through Armeria. As a result, you get the following bonuses:

- Your webapp gets HTTP/2 support for free even if Tomcat does not support it.
- You can run your RPC services on the same JVM and port as your webapp with no performance loss.

For more information, please refer to the API documentation of `TomcatService`_ and `TomcatServiceBuilder`_.
