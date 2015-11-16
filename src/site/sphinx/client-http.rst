.. _`SimpleHttpClient`: apidocs/index.html?com/linecorp/armeria/client/http/SimpleHttpClient.html
.. _`SimpleHttpRequestBuilder`: apidocs/index.html?com/linecorp/armeria/client/http/SimpleHttpRequestBuilder.html

Using Armeria as an HTTP client
===============================
For more information, please refer to the API documentation of `SimpleHttpClient`_ and `SimpleHttpRequestBuilder`_.

.. code-block:: java

    SimpleHttpClient httpClient = Clients.newClient(
            "none+http://example.com/", SimpleHttpClient.class);

    SimpleHttpRequest req =
            SimpleHttpRequestBuilder.forGet("/foo/bar.json")
                                    .header("Accept", "application/json")
                                    .build();

    Future<SimpleHttpResponse> f = httpClient.execute(req);
    SimpleHttpResponse res = f.sync().getNow()
