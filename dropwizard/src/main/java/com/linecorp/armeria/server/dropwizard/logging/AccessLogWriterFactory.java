package com.linecorp.armeria.server.dropwizard.logging;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.linecorp.armeria.server.logging.AccessLogWriter;

import io.dropwizard.jackson.Discoverable;

@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        property = "type"
)
public interface AccessLogWriterFactory extends Discoverable {
    AccessLogWriter getWriter();
}
