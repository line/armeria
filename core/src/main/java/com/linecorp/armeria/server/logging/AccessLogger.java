/*
 * Copyright 2017 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.logging;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.VirtualHost;

/**
 * A user may configure an access logger as follows.
 *
 * <p>For logback.xml:
 * <pre>{@code
 * <configuration>
 *   <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
 *     <encoder>
 *       <pattern>%-4relative [%thread] %-5level %logger{35} - %msg%n</pattern>
 *     </encoder>
 *   </appender>
 *
 *   <appender name="ACCESS" class="ch.qos.logback.core.rolling.RollingFileAppender">
 *     <file>access.log</file>
 *     <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
 *       <!-- daily rollover -->
 *       <fileNamePattern>access.%d{yyyy-MM-dd}-%i.log</fileNamePattern>
 *       <!-- each file should be at most 1GB, keep 30 days worth of history, but at most 30GB -->
 *       <maxFileSize>1GB</maxFileSize>
 *       <maxHistory>30</maxHistory>
 *       <totalSizeCap>30GB</totalSizeCap>
 *     </rollingPolicy>
 *     <encoder>
 *       <pattern>%msg%n</pattern>
 *     </encoder>
 *   </appender>
 *
 *   <logger name="com.linecorp.armeria.logging.access" level="INFO" additivity="false">
 *     <appender-ref ref="ACCESS"/>
 *   </logger>
 *
 *   <root level="DEBUG">
 *     <appender-ref ref="CONSOLE"/>
 *   </root>
 * </configuration>
 * }</pre>
 *
 * <p>For log4j2.xml:
 * <pre>{@code
 * <?xml version="1.0" encoding="UTF-8"?>
 * <Configuration status="WARN">
 *   <Appenders>
 *     <Console name="CONSOLE" target="SYSTEM_OUT">
 *       <PatternLayout pattern="%d{HH:mm:ss.SSS} [%t] %-5level %logger{36} - %msg%n"/>
 *     </Console>
 *     <RollingFile name="ACCESS" fileName="access.log" filePattern="access.%d{MM-dd-yyyy}-%i.log.gz">
 *       <PatternLayout>
 *         <Pattern>%m%n</Pattern>
 *       </PatternLayout>
 *
 *       <Policies>
 *         <!-- daily rollover -->
 *         <TimeBasedTriggeringPolicy/>
 *         <!-- each file should be at most 1GB -->
 *         <SizeBasedTriggeringPolicy size="1GB"/>
 *       </Policies>
 *       <-- keep 30 archives -->
 *       <DefaultRolloverStrategy max="30"/>
 *     </RollingFile>
 *   </Appenders>
 *
 *   <Loggers>
 *     <Logger name="com.linecorp.armeria.logging.access" level="INFO" additivity="false">
 *       <AppenderRef ref="ACCESS"/>
 *     </Logger>
 *     <Root level="DEBUG">
 *       <AppenderRef ref="CONSOLE"/>
 *     </Root>
 *   </Loggers>
 * </Configuration>
 * }</pre>
 */
final class AccessLogger {
    private static final Logger logger = LoggerFactory.getLogger(AccessLogger.class);

    /**
     * Writes an access log for the specified {@link RequestLog}.
     */
    static void write(List<AccessLogComponent> format, RequestLog log) {
        final VirtualHost host = ((ServiceRequestContext) log.context()).config().virtualHost();
        final Logger logger = host.accessLogger();
        if (!format.isEmpty() && logger.isInfoEnabled()) {
            logger.info(format(format, log));
        }
    }

    static String format(List<AccessLogComponent> format, RequestLog log) {
        final StringBuilder message = new StringBuilder();

        for (final AccessLogComponent component : format) {
            final boolean addQuote = component.addQuote();
            try {
                @Nullable final Object text = component.getMessage(log);
                if (text != null) {
                    if (addQuote) {
                        escapeAndQuote(message, text.toString());
                    } else {
                        message.append(text);
                    }
                } else {
                    appendEmptyField(message, addQuote);
                }
            } catch (Throwable e) {
                logger.debug("Caught an exception while formatting an access log:", e);
                appendEmptyField(message, addQuote);
            }
        }
        return message.toString();
    }

    private static void appendEmptyField(StringBuilder message, boolean addQuote) {
        if (addQuote) {
            message.append("\"-\"");
        } else {
            message.append('-');
        }
    }

    @VisibleForTesting
    static StringBuilder escapeAndQuote(StringBuilder message, String input) {
        message.append('"');
        boolean isEscaped = false;
        for (int i = 0; i < input.length(); i++) {
            final char c = input.charAt(i);
            if (c == '\\') {
                isEscaped = true;
            } else {
                if (c == '"' && !isEscaped) {
                    // We escape only '"' for a log message.
                    message.append('\\');
                }
                isEscaped = false;
            }
            message.append(c);
        }
        message.append('"');
        return message;
    }

    private AccessLogger() {}
}
