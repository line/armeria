.. _`Logback`: https://logback.qos.ch/
.. _`RequestContextExportingAppender`: apidocs/index.html?com/linecorp/armeria/common/logback/RequestContextExportingAppender.html
.. _`BuiltInProperty`: apidocs/index.html?com/linecorp/armeria/common/logback/BuiltInProperty.html
.. _`RequestContext`: apidocs/index.html?com/linecorp/armeria/common/RequestContext.html
.. _`MDC`: https://logback.qos.ch/manual/mdc.html

.. _advanced-logging:

Logging contextual information
==============================
With Armeria's `Logback`_ integration, you can log the properties of the `RequestContext`_ of the
request being handled. `RequestContextExportingAppender`_ is a Logback appender that exports the properties
of the current ``RequestContext`` to `MDC`_ (mapped diagnostic context).

For example, the following configuration:

.. code-block:: xml

    <?xml version="1.0" encoding="UTF-8"?>
    <configuration>
      <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
          <pattern>%d{HH:mm:ss.SSS} %X{remote.ip} %X{tls.cipher}
                   %X{req.http_headers.user-agent} %X{attrs.some_value} %msg%n</pattern>
        </encoder>
      </appender>

      <appender name="RCEA" class="com.linecorp.armeria.common.logback.RequestContextExportingAppender">
        <appender-ref ref="CONSOLE" />
        <export>remote.ip</export>
        <export>tls.cipher</export>
        <export>req.http_headers.user-agent</export>
        <export>attrs.req_id:com.example.AttrKeys#SOME_VALUE</export>
        <!-- ... or alternatively:
        <exports>remote.ip, remote.port, tls.cipher,
                 req.http_headers.user-agent,
                 attrs.some_value:com.example.AttrKeys#SOME_VALUE</exports>
        -->
      </appender>
      ...
    </configuration>

will define an appender called ``RCEA`` which exports the following:

- ``remote.ip``

  - the IP address of the remote peer,

- ``tls.cipher``

  - the SSL/TLS cipher suite of the connection,

- ``req.http_headers.user-agent``

  - the user agent of the client,

- ``attrs.some_value``

  - a custom attribute set via ``RequestContext.attr(AttributeKey.valueOf(AttrKeys.class, "SOME_VALUE")).set(...)``

... to the `MDC`_ property map and forwards the log message to the appender ``CONSOLE``, as defined in the
``<appender-ref />`` element.

There are three types of properties you can export using `RequestContextExportingAppender`_.

Built-in properties
-------------------
A built-in property is a common property available for most requests. See the complete list of the built-in
properties and their MDC keys at `BuiltInProperty`_.

HTTP request and response headers
---------------------------------
When the session protocol of the current connection is HTTP, a user can export HTTP headers of the current
request and response. The MDC key of the exported header is ``"req.http_headers.<lower-case header name>"`` or
``"res.http_headers.<lower-case header name>"``. For example:

- ``"req.http_headers.user-agent"``
- ``"res.http_headers.set-cookie"``

Custom attributes
-----------------
A user can attach an arbitrary custom attribute to a ``RequestContext`` by using
``RequestContext.attr(...).set(...)`` to store the information associated with the request being handled.
`RequestContextExportingAppender`_ can export such attributes to the `MDC`_ property map as well.

Unlike other property types, you need to specify the full name of an attribute as well as its alias.
For example, if you want to export an attribute ``com.example.Foo#ATTR_BAR`` with the alias ``bar``, you need to add
``<export>attrs.bar:com.example.Foo#ATTR_BAR</export>`` to the XML configuration. The resulting MDC key to
access the attribute value is ``attrs.bar``, which follows the form of ``attrs.<alias>``.

Using an alternative string converter for a custom attribute
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
By default, `RequestContextExportingAppender`_ uses ``Object.toString()`` to convert an attribute value into
an `MDC`_ value. If you want an alternative string repredentation of an attribute value, you can define
a ``Function`` class with a public no-args constructor that transforms an attribute value into a ``String``:

.. code-block:: java

    package com.example;

    public class SomeValue {
        public final String value;

        @Override
        public String toString() {
            // Too verbose for logging
            return "SomeValue(value=" + value + ')';
        }
    }

    public class MyStringifier implements Function<SomeValue, String> {
        @Override
        public String apply(SomeValue o) {
            return o.value;
        }
    }

Once the ``Function`` is implemented, specify the fully-qualified class name of the ``Function`` implementation
as the 3rd component of the ``<export />`` element in the XML configuration:

.. code-block:: xml

    <?xml version="1.0" encoding="UTF-8"?>
    <configuration>
      ...
      <appender name="RCEA" class="com.linecorp.armeria.common.logback.RequestContextExportingAppender">
        ...
        <export>attrs.some_value:com.example.AttrKeys#SOME_VALUE:com.example.MyStringifier</export>
        ...
      </appender>
      ...
    </configuration>
