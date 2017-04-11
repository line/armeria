.. _`com.linecorp.armeria.client.http`: apidocs/index.html?com/linecorp/armeria/client/http/package-summary.html

.. _client-http:

Calling an HTTP service
=======================
For more information, please refer to the API documentation of the `com.linecorp.armeria.client.http`_ package.

.. code-block:: java

    import com.linecorp.armeria.client.Clients;
    import com.linecorp.armeria.client.http.HttpClient;
    import com.linecorp.armeria.common.http.AggregatedHttpMessage;
    import com.linecorp.armeria.common.http.HttpHeaderNames;
    import com.linecorp.armeria.common.http.HttpHeaders;
    import com.linecorp.armeria.common.http.HttpMethod;

    HttpClient httpClient = Clients.newClient(
            "none+http://example.com/", HttpClient.class);

    AggregatedHttpMessage textResponse = httpClient.get("/foo/bar.txt").aggregate().join();

    AggregatedHttpMessage getJson = AggregatedHttpMessage.of(
            HttpHeaders.of(HttpMethod.GET, "/foo/bar.json")
                       .set(HttpHeaderNames.ACCEPT, "application/json"));

    AggregatedHttpMessage jsonResponse = httpClient.execute(getJson).aggregate().join();

See also
--------

- :ref:`client-retrofit`
