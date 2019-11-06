package com.linecorp.armeria.server.dropwizard.logging;

import com.fasterxml.jackson.annotation.JsonTypeName;
import com.linecorp.armeria.server.logging.AccessLogWriter;

@JsonTypeName("combined")
public class CombinedAccessLogWriterFactory implements AccessLogWriterFactory {

    public CombinedAccessLogWriterFactory() {
    }

    @Override
    public AccessLogWriter getWriter() {
        return AccessLogWriter.combined();
    }
}
