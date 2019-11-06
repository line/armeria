package com.linecorp.armeria.server.dropwizard.logging;

import com.fasterxml.jackson.annotation.JsonTypeName;
import com.linecorp.armeria.server.logging.AccessLogWriter;

@JsonTypeName("common")
public class CommonAccessLogWriterFactory implements AccessLogWriterFactory {

    public CommonAccessLogWriterFactory() {
    }

    @Override
    public AccessLogWriter getWriter() {
        return AccessLogWriter.common();
    }
}
