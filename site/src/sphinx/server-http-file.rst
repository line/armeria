.. _`HttpFileService`: apidocs/index.html?com/linecorp/armeria/server/http/file/HttpFileService.html
.. _`HttpFileServiceBuilder`: apidocs/index.html?com/linecorp/armeria/server/http/file/HttpFileServiceBuilder.html

Serving static files
====================
For more information, please refer to the API documentation of `HttpFileService`_ and `HttpFileServiceBuilder`_.

.. code-block:: java

    import com.linecorp.armeria.server.ServerBuilder;
    import com.linecorp.armeria.server.http.file.HttpFileService;

    ServerBuilder sb = new ServerBuilder();
    sb.serviceUnder("/images/",
                    HttpFileService.forFileSystem("/var/lib/www/images"));

    sb.serviceUnder("/",
                    HttpFileService.forClassPath("/com/example/files"));
