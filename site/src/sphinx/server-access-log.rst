.. _ServerBuilder: apidocs/index.html?com/linecorp/armeria/server/ServerBuilder.html
.. _`RequestLog`: apidocs/index.html?com/linecorp/armeria/common/logging/RequestLog.html

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
        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
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
using `ServerBuilder`_. You may use one of the pre-defined log formats.

.. code-block:: java

    ServerBuilder sb = new ServerBuilder();
    // Use NCSA common log format.
    sb.accessLogWriter(AccessLogWriters.common());
    // Use NCSA combined log format.
    sb.accessLogWriter(AccessLogWriters.combined());
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
| ``%{ATTRIBUTE_NAME}j``    | Yes               | the value of the specified attribute name          |
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


Customizing an access log writer
--------------------------------

You can specify your own log writer which implements Consumer<`RequestLog`_ >.

.. code-block:: java

    ServerBuilder sb = new ServerBuilder();
    sb.accessLogWriter(requestLog -> {
        // Write your access log with the given RequestLog instance.
        ....
    });
