.. _client-http:

Calling an HTTP service
=======================

.. code-block:: java

    import com.linecorp.armeria.client.HttpClient;
    import com.linecorp.armeria.common.AggregatedHttpMessage;
    import com.linecorp.armeria.common.HttpHeaderNames;
    import com.linecorp.armeria.common.HttpHeaders;
    import com.linecorp.armeria.common.HttpMethod;

    HttpClient httpClient = HttpClient.of("http://example.com/");

    AggregatedHttpMessage textResponse = httpClient.get("/foo/bar.txt").aggregate().join();

    AggregatedHttpMessage getJson = AggregatedHttpMessage.of(
            HttpHeaders.of(HttpMethod.GET, "/foo/bar.json")
                       .set(HttpHeaderNames.ACCEPT, "application/json"));

    AggregatedHttpMessage jsonResponse = httpClient.execute(getJson).aggregate().join();

See also
--------

- :ref:`client-retrofit`
