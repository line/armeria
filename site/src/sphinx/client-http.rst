.. _client-http:

Calling an HTTP service
=======================

.. code-block:: java

    import com.linecorp.armeria.client.HttpClient;
    import com.linecorp.armeria.common.AggregatedHttpResponse;
    import com.linecorp.armeria.common.HttpHeaderNames;
    import com.linecorp.armeria.common.HttpMethod;
    import com.linecorp.armeria.common.RequestHeaders;

    HttpClient httpClient = HttpClient.of("http://example.com/");

    // Send a simple GET request.
    AggregatedHttpResponse res1 = httpClient.get("/foo/bar.txt").aggregate().join();

    // Send a GET request with an additional header.
    RequestHeaders getJson = RequestHeaders.of(HttpMethod.GET, "/foo/bar.json",
                                               HttpHeaderNames.ACCEPT, "application/json");

    AggregatedHttpResponse res2 = httpClient.execute(getJson).aggregate().join();

    // Send a simple POST request encoded in UTF-8.
    AggregatedHttpResponse res3 = httpClient.post("/upload", "{ \"foo\": \"bar\" }")
                                            .aggregate().join();

See also
--------

- :ref:`client-retrofit`
