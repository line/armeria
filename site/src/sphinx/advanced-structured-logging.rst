.. _`RequestLog`: apidocs/index.html?com/linecorp/armeria/common/logging/RequestLog.html
.. _`RequestLogAvailability`: apidocs/index.html?com/linecorp/armeria/common/logging/RequestLogAvailability.html
.. _`RequestContext`: apidocs/index.html?com/linecorp/armeria/common/RequestContext.html

.. _advanced-structured-logging:

Structured logging
==================
Although traditional logging is a useful tool to diagnose the behavior of an application, it has its own
problem; the resulting log messages are not always machine-friendly. This section explains the Armeria API for
retrieving the information collected during request life cycle in a machine-friendly way.

What properties can be retrieved?
---------------------------------
`RequestLog`_ provides all the properties you can retrieve:

+----------------------------------------------------------------------------------------------------+
| Request properties                                                                                 |
+=============================+======================================================================+
| ``requestStartTimeMillis``  | when the request processing started                                  |
+-----------------------------+----------------------------------------------------------------------+
| ``requestDurationNanos``    | the duration took to process the request completely                  |
+-----------------------------+----------------------------------------------------------------------+
| ``requestLength``           | the byte length of the request content                               |
+-----------------------------+----------------------------------------------------------------------+
| ``requestCause``            | the cause of request processing failure (if any)                     |
+-----------------------------+----------------------------------------------------------------------+
| ``sessionProtocol``         | the protocol of the connection (e.g. ``H2C``)                        |
+-----------------------------+----------------------------------------------------------------------+
| ``serializationFormat``     | the serialization format of the content (e.g. ``tbinary``, ``none``) |
+-----------------------------+----------------------------------------------------------------------+
| ``host``                    | the name of the virtual host that serves the request                 |
+-----------------------------+----------------------------------------------------------------------+
| ``method``                  | the method of the request (e.g. ``GET``, ``POST``)                   |
+-----------------------------+----------------------------------------------------------------------+
| ``path``                    | the path of the request (e.g. ``/thrift/foo``)                       |
+-----------------------------+----------------------------------------------------------------------+
| ``query``                   | the query of the request (e.g. ``foo=bar&bar=baz``)                  |
+-----------------------------+----------------------------------------------------------------------+
| ``requestEnvelope``         | the protocol-dependent envelope object of the request.               |
|                             | ``HttpHeaders`` for HTTP.                                            |
+-----------------------------+----------------------------------------------------------------------+
| ``requestContent``          | the serialization-dependent content object of the request.           |
|                             | ``ThriftCall`` for Thrift. ``null`` otherwise.                       |
+-----------------------------+----------------------------------------------------------------------+

+-----------------------------+----------------------------------------------------------------------+
| Response properties                                                                                |
+=============================+======================================================================+
| ``responseStartTimeMillis`` | when the response processing started                                 |
+-----------------------------+----------------------------------------------------------------------+
| ``responseDurationNanos``   | the duration took to process the response completely                 |
+-----------------------------+----------------------------------------------------------------------+
| ``responseLength``          | the byte length of the response content                              |
+-----------------------------+----------------------------------------------------------------------+
| ``responseCause``           | the cause of response processing failure (if any)                    |
+-----------------------------+----------------------------------------------------------------------+
| ``totalDurationNanos``      | the duration between the request start and the response end          |
|                             | (i.e. response time)                                                 |
+-----------------------------+----------------------------------------------------------------------+
| ``statusCode``              | the integer status code (e.g. 404)                                   |
+-----------------------------+----------------------------------------------------------------------+
| ``responseEnvelope``        | the protocol-dependent envelope object of the response.              |
|                             | ``HttpHeaders`` for HTTP.                                            |
+-----------------------------+----------------------------------------------------------------------+
| ``responseContent``         | the serialization-dependent content object of the response.          |
|                             | ``ThriftReply`` for Thrift. ``null`` otherwise.                      |
+-----------------------------+----------------------------------------------------------------------+

Availability of properties
--------------------------
Armeria handles requests and responses in a stream-oriented way, which means that some properties are revealed
only after the streams are processed to some point. For example, there's no way to know the ``requestLength``
until the request processing ends. Also, some properties related to the (de)serialization of request content,
such as ``serializationFormat`` and ``requestContent``, will not be available when request processing just
started.

To get notified when a certain set of properties are available, you can add a listener to a ``RequestLog``:

.. code-block:: java

    import com.linecorp.armeria.common.HttpRequest;
    import com.linecorp.armeria.common.HttpResponse;
    import com.linecorp.armeria.common.logging.RequestLog;
    import com.linecorp.armeria.common.logging.RequestLogAvailability;
    import com.linecorp.armeria.server.ServiceRequestContext;
    import com.linecorp.armeria.server.AbstractHttpService;

    public class MyService extends AbstractHttpService {
        @Override
        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) {
            final RequestLog log = ctx.log();

            log.addListener(log -> {
                System.err.println("Handled a request: " + log);
            }, RequestLogAvailability.COMPLETE);

            return super.serve(ctx, req);
        }
    }

Note that `RequestLogAvailability`_ is specified when adding a listener. `RequestLogAvailability`_ is an enum
that is used to express which `RequestLog`_ properties you are interested in. ``COMPLETE`` will make your
listener invoked when all properties are available.

Set ``serializationFormat`` and ``requestContent`` early if possible
--------------------------------------------------------------------
Armeria depends on the ``serializationFormat`` and ``requestContent`` property to determine whether a request
is an RPC and what the method name of the call is. If you are sure the request you handle is not an RPC, set
the ``serializationFormat`` and ``requestContent`` property explicitly to ``NONE`` and ``null`` so that Armeria
and other log listeners get the information sooner:

.. code-block:: java

    import com.linecorp.armeria.common.HttpRequest;
    import com.linecorp.armeria.common.HttpResponse;
    import com.linecorp.armeria.common.SerializationFormat;
    import com.linecorp.armeria.server.ServiceRequestContext;
    import com.linecorp.armeria.server.HttpService;

    public class MyService implements HttpService {
        @Override
        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) {
            ctx.logBuilder().serializationFormat(SerializationFormat.NONE);
            ctx.logBuilder().requestContent(null);
            ...
        }
    }

Consider using ``AbstractHttpService`` which sets the ``serializationFormat`` and ``requestContent``
automatically for you:

.. code-block:: java

    import com.linecorp.armeria.common.HttpResponseWriter;
    import com.linecorp.armeria.common.thrift.ThriftSerializationFormats;
    import com.linecorp.armeria.server.AbstractHttpService;

    public class MyService extenda AbstractHttpService {
        @Override
        public void doGet(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res) {
            // serializationFormat and requestContent will be set to NONE and null
            // automatically when this method returns.
            ...
        }

        @Override
        public void doPost(ServiceRequestContext ctx, HttpRequest req, HttpResponseWriter res) {
            // Set serializationFormat explicitly.
            ctx.logBuilder().serializationFormat(ThriftSerializationFormats.BINARY);
            // This will prevent AbstractHttpService from setting requestContent to null
            // automatically. You should call RequestLogBuilder.requestContent(...) later
            // when the content is determined.
            ctx.logBuilder().deferRequestContent();
            // Alternatively, you can set requestContent right here:
            // ctx.logBuilder().requestContent(...);
            ...
        }
    }
