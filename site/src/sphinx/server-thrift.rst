.. _`Calling a Thrift service`: client-thrift.html
.. _`ServerBuilder`: apidocs/index.html?com/linecorp/armeria/server/ServerBuilder.html
.. _`THttpService`: apidocs/index.html?com/linecorp/armeria/server/thrift/THttpService.html
.. _`TMultiplexedProcessor`: https://github.com/apache/thrift/blob/400b346db2510fffa06c0ced11105e3618ce5367/lib/java/src/org/apache/thrift/TMultiplexedProcessor.java#L28

.. _server-thrift:

Running a Thrift service
========================

Let's assume we have the following Thrift IDL:

.. code-block:: thrift

    namespace java com.example.thrift.hello

    service HelloService {
        string hello(1:string name)
    }

The Apache Thrift compiler will produce some Java code under the ``com.example.thrift.hello`` package.
The most noteworthy one is ``HelloService.java`` which defines the service interfaces we will implement:

.. code-block:: java

    import org.apache.thrift.TException;
    import org.apache.thrift.async.AsyncMethodCallback;

    public class HelloService {
        public interface Iface {
            public String hello(String name) throws TException;
        }

        public interface AsyncIface {
            public void hello(String name, AsyncMethodCallback<String> resultHandler) throws TException;
        }
        ...
    }

If you are interested in going fully asynchronous, it is recommended to implement the ``AsyncIface`` interface,
although it is easier to implement the synchronous ``Iface`` interface:

.. code-block:: java

    import org.apache.thrift.TException;
    import org.apache.thrift.async.AsyncMethodCallback;

    public class MyHelloService implements HelloService.AsyncIface {
        @Override
        public void hello(String name, AsyncMethodCallback<String> resultHandler) {
            resultHandler.onComplete("Hello, " + name + '!');
        }
    }

    // or synchronously:
    public class MySynchronousHelloService implements HelloService.Iface {
        @Override
        public String hello(String name) throws TException {
            return "Hello, " + name + '!';
        }
    }

``THttpService``
----------------

Once you've finished the implementation of the interface, you need to wrap it with a `THttpService`_ and add it
to the `ServerBuilder`_:

.. code-block:: java

    ServerBuilder sb = new ServerBuilder();
    ...
    sb.service("/hello", THttpService.of(new MyHelloService()));
    ...
    Server server = sb.build();
    server.start();

Serialization formats
---------------------

`THttpService`_ supports four Thrift serialization formats: TBINARY, TCOMPACT, TJSON and TTEXT. It chooses
the serialization format based on the value of the ``content-type`` HTTP header.

+--------------------------------------------------+----------------------------------------+
| Header value                                     | Serialization format                   |
+==================================================+========================================+
| | Unspecified or                                 | | Use the default serialization format |
| | ``application/x-thrift``                       | | (TBINARY unless specified)           |
+--------------------------------------------------+----------------------------------------+
| | ``application/x-thrift; protocol=TBINARY`` or  | TBINARY                                |
| | ``vnd.apache.thrift.binary``                   |                                        |
+--------------------------------------------------+----------------------------------------+
| | ``application/x-thrift; protocol=TCOMPACT`` or | TCOMPACT                               |
| | ``vnd.apache.thrift.compact``                  |                                        |
+--------------------------------------------------+----------------------------------------+
| | ``application/x-thrift; protocol=TJSON`` or    | TJSON                                  |
| | ``vnd.apache.thrift.json``                     |                                        |
+--------------------------------------------------+----------------------------------------+
| | ``application/x-thrift; protocol=TTEXT`` or    | TTEXT                                  |
| | ``vnd.apache.thrift.text``                     |                                        |
+--------------------------------------------------+----------------------------------------+

To change the default serialization format from TBINARY to something else, specify it when creating a
`THttpService`_:

.. code-block:: java

    import com.linecorp.armeria.common.thrift.ThriftSerializationFormats;

    ServerBuilder sb = new ServerBuilder();
    // Use TCOMACT as the default serialization format.
    sb.service("/hello", THttpService.of(new MyHelloService(),
                                         ThriftSerializationFormats.COMPACT));

You can also choose the list of allowed serialization formats:

.. code-block:: java

    ServerBuilder sb = new ServerBuilder();
    // Use TBINARY as the default serialization format.
    // Allow TBINARY and TCOMPACT only.
    sb.service("/hello", THttpService.of(new MyHelloService(),
                                         ThriftSerializationFormats.BINARY,
                                         ThriftSerializationFormats.COMPACT));

.. note::
   TTEXT is not designed for efficiency and is recommended to be only used for debugging.
   It's best to serve from a separate path only accessible internally.

Service multiplexing
--------------------

`THttpService`_ supports service multiplexing fully compatible with Apache Thrift `TMultiplexedProcessor`_.

.. code-block:: java

    Map<String, Object> impls = new HashMap<>();
    impls.put("foo", new MyFooService());
    impls.put("bar", new MyBarService());
    // Use MyHelloService for non-multiplexed requests.
    impls.put("", new MyHelloService());

    sb.service("/thrift", THttpService.of(impls));

See also
--------

- :ref:`client-thrift`
