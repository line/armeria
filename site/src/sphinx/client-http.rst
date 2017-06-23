.. _client-http:

Calling an HTTP service
=======================

.. code-block:: java

    import com.linecorp.armeria.client.Clients;
    import com.linecorp.armeria.client.HttpClient;
    import com.linecorp.armeria.common.AggregatedHttpMessage;
    import com.linecorp.armeria.common.HttpHeaderNames;
    import com.linecorp.armeria.common.HttpHeaders;
    import com.linecorp.armeria.common.HttpMethod;

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
