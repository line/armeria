.. _DateTimeFormatter: https://docs.oracle.com/javase/10/docs/api/java/time/format/DateTimeFormatter.html

.. _server-access-log:

Writing an access log
=====================

Configuring logging framework
-----------------------------

To write an access log, you need to configure a logging framework first. The following configurations are
simple examples of ``logback.xml`` and ``log4j2.xml`` respectively.

logback
^^^^^^^

.. code-block:: xml

    <?xml version="1.0" encoding="UTF-8"?>
    <configuration>
      <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
          <pattern>%-4relative [%thread] %-5level %logger{35} - %msg%n</pattern>
        </encoder>
      </appender>

      <appender name="ACCESS" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>access.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
          <!-- daily rollover -->
          <fileNamePattern>access.%d{yyyy-MM-dd}-%i.log</fileNamePattern>
          <!-- each file should be at most 1GB, keep 30 days worth of history, but at most 30GB -->
          <maxFileSize>1GB</maxFileSize>
          <maxHistory>30</maxHistory>
          <totalSizeCap>30GB</totalSizeCap>
        </rollingPolicy>
        <encoder>
          <pattern>%msg%n</pattern>
        </encoder>
      </appender>

      <logger name="com.linecorp.armeria.logging.access" level="INFO" additivity="false">
        <appender-ref ref="ACCESS"/>
      </logger>

      <root level="DEBUG">
        <appender-ref ref="CONSOLE"/>
      </root>
    </configuration>


log4j2
^^^^^^

.. code-block:: xml

    <?xml version="1.0" encoding="UTF-8"?>
    <Configuration status="WARN">
      <Appenders>
        <Console name="CONSOLE" target="SYSTEM_OUT">
          <PatternLayout pattern="%d{HH:mm:ss.SSS} [%t] %-5level %logger{36} - %msg%n"/>
        </Console>
        <RollingFile name="ACCESS" fileName="access.log"
                     filePattern="access.%d{MM-dd-yyyy}-%i.log.gz">
          <PatternLayout>
            <Pattern>%m%n</Pattern>
          </PatternLayout>

          <Policies>
            <!-- daily rollover -->
            <TimeBasedTriggeringPolicy/>
            <!-- each file should be at most 1GB -->
            <SizeBasedTriggeringPolicy size="1GB"/>
          </Policies>
          <!-- keep 30 archives -->
          <DefaultRolloverStrategy max="30"/>
        </RollingFile>
      </Appenders>

      <Loggers>
        <Logger name="com.linecorp.armeria.logging.access" level="INFO" additivity="false">
          <AppenderRef ref="ACCESS"/>
        </Logger>
        <Root level="DEBUG">
          <AppenderRef ref="CONSOLE"/>
        </Root>
      </Loggers>
    </Configuration>

Customizing a log format
------------------------

Access logging is disabled by default. If you want to enable it, you need to specify an access log writer
using :api:`ServerBuilder`. You may use one of the pre-defined log formats.

.. code-block:: java

    ServerBuilder sb = new ServerBuilder();
    // Use NCSA common log format.
    sb.accessLogWriter(AccessLogWriter.common());
    // Use NCSA combined log format.
    sb.accessLogWriter(AccessLogWriter.combined());
    // Use your own log format.
    sb.accessLogFormat("...log format...");
    ...


Pre-defined log formats are as follows.

+---------------+------------------------------------------------------------------------------------+
| Name          | Format                                                                             |
+===============+====================================================================================+
| ``common``    | ``%h %l %u %t "%r" %s %b``                                                         |
+---------------+------------------------------------------------------------------------------------+
| ``combined``  | ``%h %l %u %t "%r" %s %b "%{Referer}i" "%{User-Agent}i" "%{Cookie}i"``             |
+---------------+------------------------------------------------------------------------------------+

Tokens for the log format are listed in the following table.

+---------------------------+-------------------+----------------------------------------------------+
| Tokens                    | Condition support | Description                                        |
+===========================+===================+====================================================+
| ``%A``                    | No                | the local IP address                               |
+---------------------------+-------------------+----------------------------------------------------+
| ``%a``                    | No                | the IP address of the client who initiated a       |
|                           |                   | request. Use ``%{c}a`` format string to get the    |
|                           |                   | remote IP address where the channel is connected   |
|                           |                   | to, which may yield a different value when there   |
|                           |                   | is an intermediary proxy server.                   |
+---------------------------+-------------------+----------------------------------------------------+
| ``%h``                    | No                | the remote hostname or IP address if DNS           |
|                           |                   | hostname lookup is not available                   |
+---------------------------+-------------------+----------------------------------------------------+
| ``%l``                    | No                | the remote logname of the user                     |
|                           |                   | (not supported yet, always write ``-``)            |
+---------------------------+-------------------+----------------------------------------------------+
| ``%u``                    | No                | the name of the authenticated remote user          |
|                           |                   | (not supported yet, always write ``-``)            |
+---------------------------+-------------------+----------------------------------------------------+
| ``%t``                    | No                | the date, time and time zone that the request      |
|                           |                   | was received, by default in ``strftime`` format    |
|                           |                   | %d/%b/%Y:%H:%M:%S %z.                              |
|                           |                   | (for example, ``10/Oct/2000:13:55:36 -0700``)      |
|                           |                   | Refer to :ref:`timestamp-format` for more          |
|                           |                   | information.                                       |
+---------------------------+-------------------+----------------------------------------------------+
| ``%r``                    | Yes               | the request line from the client                   |
|                           |                   | (for example, ``GET /path h2``)                    |
+---------------------------+-------------------+----------------------------------------------------+
| ``%s``                    | No                | the HTTP status code returned to the client        |
+---------------------------+-------------------+----------------------------------------------------+
| ``%b``                    | Yes               | the size of the object returned to the client,     |
|                           |                   | measured in bytes                                  |
+---------------------------+-------------------+----------------------------------------------------+
| ``%{HEADER_NAME}i``       | Yes               | the value of the specified HTTP request header     |
|                           |                   | name                                               |
+---------------------------+-------------------+----------------------------------------------------+
| ``%{HEADER_NAME}o``       | Yes               | the value of the specified HTTP response header    |
|                           |                   | name                                               |
+---------------------------+-------------------+----------------------------------------------------+
| ``%{ATTRIBUTE_NAME}j``    | Yes               | the value of the specified attribute name          |
+---------------------------+-------------------+----------------------------------------------------+
| ``%{REQUEST_LOG_NAME}L``  | Yes               | the value of the specified variable of the         |
|                           |                   | :api:`RequestLog`. Refer to :ref:`request-log`     |
|                           |                   | for more information.                              |
+---------------------------+-------------------+----------------------------------------------------+

Some tokens can have a condition of the response status code and the log message can be omitted with
the condition.

+---------------------------------------------------+------------------------------------------------+
| Example of a condition                            | Description                                    |
+===================================================+================================================+
| ``%200b``                                         | Write the size of the object returned to the   |
|                                                   | client only if the response code is ``200``.   |
+---------------------------------------------------+------------------------------------------------+
| ``%200,304{User-Agent}i``                         | Write ``User-Agent`` header value only if the  |
|                                                   | response code is ``200`` or ``304``.           |
+---------------------------------------------------+------------------------------------------------+
| ``%!200,304{com.example.armeria.Attribute#KEY}j`` | Write the value of the specified attribute     |
|                                                   | only if the response code is neither ``200``   |
|                                                   | nor ``304``.                                   |
+---------------------------------------------------+------------------------------------------------+

.. _request-log:

Retrieving values from RequestLog
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

:api:`RequestLog` holds information about the request, so a user may want to write these values to his or
her access log file. To write them in a simple way, ``%{variable}L`` token is provided with the following
supported variable:

+-------------------------------+--------------------------------------------------------------------+
| Name                          | Description                                                        |
+===============================+====================================================================+
| ``method``                    | the HTTP method value of the request                               |
+-------------------------------+--------------------------------------------------------------------+
| ``path``                      | the absolute path part of the HTTP request URI                     |
+-------------------------------+--------------------------------------------------------------------+
| ``query``                     | the query part of the HTTP request URI                             |
+-------------------------------+--------------------------------------------------------------------+
| ``requestStartTimeMillis``    | the time when the processing of the request started,               |
|                               | in milliseconds since the epoch                                    |
+-------------------------------+--------------------------------------------------------------------+
| ``requestDurationMillis``     | the duration that was taken to consume or produce the request      |
|                               | completely, in milliseconds                                        |
+-------------------------------+--------------------------------------------------------------------+
| ``requestDurationNanos``      | the duration that was taken to consume or produce the request      |
|                               | completely, in nanoseconds                                         |
+-------------------------------+--------------------------------------------------------------------+
| ``requestLength``             | the length of the request content                                  |
+-------------------------------+--------------------------------------------------------------------+
| ``requestCause``              | the cause of request processing failure. The class name of the     |
|                               | cause and the detail message of it will be contained if exists.    |
+-------------------------------+--------------------------------------------------------------------+
| ``requestContentPreview``     | the preview of the request content                                 |
+-------------------------------+--------------------------------------------------------------------+
| ``responseStartTimeMillis``   | the time when the processing of the response started,              |
|                               | in milliseconds since the epoch                                    |
+-------------------------------+--------------------------------------------------------------------+
| ``responseDurationMillis``    | the duration that was taken to consume or produce the response     |
|                               | completely, in milliseconds                                        |
+-------------------------------+--------------------------------------------------------------------+
| ``responseDurationNanos``     | the duration that was taken to consume or produce the response     |
|                               | completely, in nanoseconds                                         |
+-------------------------------+--------------------------------------------------------------------+
| ``responseLength``            | the length of the response content                                 |
+-------------------------------+--------------------------------------------------------------------+
| ``responseCause``             | the cause of response processing failure. The class name of the    |
|                               | cause and the detail message of it will be contained if exists.    |
+-------------------------------+--------------------------------------------------------------------+
| ``responseContentPreview``    | the preview of the response content                                |
+-------------------------------+--------------------------------------------------------------------+
| ``totalDurationMillis``       | the amount of time taken since the request processing started and  |
|                               | until the response processing ended, in milliseconds               |
+-------------------------------+--------------------------------------------------------------------+
| ``totalDurationNanos``        | the amount of time taken since the request processing started and  |
|                               | until the response processing ended, in nanoseconds                |
+-------------------------------+--------------------------------------------------------------------+
| ``sessionProtocol``           | the session protocol of the request.                               |
|                               | e.g. ``h1``, ``h2``, ``h1c`` or ``h2c``                            |
+-------------------------------+--------------------------------------------------------------------+
| ``serializationFormat``       | the serialization format of the request.                           |
|                               | e.g. ``tbinary``, ``ttext``, ``tcompact``, ``tjson`` or ``none``   |
+-------------------------------+--------------------------------------------------------------------+
| ``scheme``                    | the scheme value printed as ``serializationFormat+sessionProtocol``|
+-------------------------------+--------------------------------------------------------------------+
| ``host``                      | the host name of the request                                       |
+-------------------------------+--------------------------------------------------------------------+
| ``status``                    | the status code and its reason phrase of the response.             |
|                               | e.g. ``200 OK``                                                    |
+-------------------------------+--------------------------------------------------------------------+
| ``statusCode``                | the status code of the response. e.g. ``200``                      |
+-------------------------------+--------------------------------------------------------------------+

.. _timestamp-format:

Customizing timestamp format
^^^^^^^^^^^^^^^^^^^^^^^^^^^^

You can specify a new date/time format for the ``%t`` token with DateTimeFormatter_. You can use one of the
following formatters which is provided by JDK as a variable of the ``%t`` token, e.g. ``%{BASIC_ISO_DATE}t``.
If you want to use your own pattern, you can specify it as the variable, e.g. ``%{yyyy MM dd}t``.

+-------------------------+----------------------------------+--------------------------------------------+
| Formatter               | Description                      | Example                                    |
+=========================+==================================+============================================+
| ``BASIC_ISO_DATE``      | Basic ISO date                   | ``20111203``                               |
+-------------------------+----------------------------------+--------------------------------------------+
| ``ISO_LOCAL_DATE``      | ISO Local Date                   | ``2011-12-03``                             |
+-------------------------+----------------------------------+--------------------------------------------+
| ``ISO_OFFSET_DATE``     | ISO Date with offset             | ``2011-12-03+01:00``                       |
+-------------------------+----------------------------------+--------------------------------------------+
| ``ISO_DATE``            | ISO Date with or without offset  | ``2011-12-03+01:00``; ``2011-12-03``       |
+-------------------------+----------------------------------+--------------------------------------------+
| ``ISO_LOCAL_TIME``      | Time without offset              | ``10:15:30``                               |
+-------------------------+----------------------------------+--------------------------------------------+
| ``ISO_OFFSET_TIME``     | Time with offset                 | ``10:15:30+01:00``                         |
+-------------------------+----------------------------------+--------------------------------------------+
| ``ISO_TIME``            | Time with or without offset      | ``10:15:30+01:00``; ``10:15:30``           |
+-------------------------+----------------------------------+--------------------------------------------+
| ``ISO_LOCAL_DATE_TIME`` | ISO Local Date and Time          | ``2011-12-03T10:15:30``                    |
+-------------------------+----------------------------------+--------------------------------------------+
| ``ISO_OFFSET_DATE_TIME``| Date Time with Offset            | ``2011-12-03T10:15:30+01:00``              |
+-------------------------+----------------------------------+--------------------------------------------+
| ``ISO_ZONED_DATE_TIME`` | Zoned Date Time                  | ``2011-12-03T10:15:30+01:00[Europe/Paris]``|
+-------------------------+----------------------------------+--------------------------------------------+
| ``ISO_DATE_TIME``       | Date and time with ZoneId        | ``2011-12-03T10:15:30+01:00[Europe/Paris]``|
+-------------------------+----------------------------------+--------------------------------------------+
| ``ISO_ORDINAL_DATE``    | Year and day of year             | ``2012-337``                               |
+-------------------------+----------------------------------+--------------------------------------------+
| ``ISO_WEEK_DATE``       | Year and Week                    | ``2012-W48-6``                             |
+-------------------------+----------------------------------+--------------------------------------------+
| ``ISO_INSTANT``         | Date and Time of an Instant      | ``2011-12-03T10:15:30Z``                   |
+-------------------------+----------------------------------+--------------------------------------------+
| ``RFC_1123_DATE_TIME``  | RFC 1123 / RFC 822               | ``Tue, 3 Jun 2008 11:05:30 GMT``           |
+-------------------------+----------------------------------+--------------------------------------------+


Customizing an access log writer
--------------------------------

You can specify your own log writer which implements a ``Consumer`` of :api:`RequestLog`.

.. code-block:: java

    ServerBuilder sb = new ServerBuilder();
    sb.accessLogWriter(requestLog -> {
        // Write your access log with the given RequestLog instance.
        ....
    });


Customizing an access logger
----------------------------

Armeria uses an SLF4J logger whose name is based on a reversed domain name of each virtual host by
default, e.g.

- ``com.linecorp.armeria.logging.access.com.example`` for ``*.example.com``
- ``com.linecorp.armeria.logging.access.com.linecorp`` for ``*.linecorp.com``

Alternatively, you can specify your own mapper or logger for a :api:`VirtualHost`, e.g.

.. code-block:: java

    ServerBuilder sb = new ServerBuilder();

    // Using the specific logger name.
    sb.accessLogger("com.example.my.access.logs");
    ....

    // Using your own logger.
    Logger logger = LoggerFactory.getLogger("com.example2.my.access.logs");
    sb.accessLogger(Logger);
    ....

    // Using the mapper which sets an access logger with the given VirtualHost instance.
    sb.accessLogger(virtualHost -> {
        // Return the logger.
        // Do not return null. Otherwise, it will raise an IllegalStateException.
        return LoggerFactory.getLogger("com.example.my.access.logs." + virtualHost.defaultHostname());
    });
    ....

You can also specify your own logger for the specific :api:`VirtualHost`.
In this case, the mapper or logger you set for a specific :api:`VirtualHost` will override the access logger set via ``ServerBuilder.accessLogger()``.

.. code-block:: java

    // Using the specific logger name.
    sb.withVirtualHost("*.example.com")
      .accessLogger("com.example.my.access.logs")
      .and()
    ....

    // Using your own logger.
    Logger logger = LoggerFactory.getLogger("com.example2.my.access.logs");
    sb.withVirtualHost("*.example2.com")
      .accessLogger(Logger)
      .and()
    ....

    // Using the mapper which sets an access logger with the given VirtualHost instance.
    sb.withVirtualHost("*.example3.com")
      .accessLogger(virtualHost -> {
        // Return the logger.
        // Do not return null. Otherwise, it will raise an IllegalStateException.
        return LoggerFactory.getLogger("com.example.my.access.logs." + virtualHost.defaultHostname());
      })
    ....
