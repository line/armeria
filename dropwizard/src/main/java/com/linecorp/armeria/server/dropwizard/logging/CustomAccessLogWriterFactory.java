package com.linecorp.armeria.server.dropwizard.logging;

import javax.validation.Valid;
import javax.validation.constraints.Size;

import org.hibernate.validator.constraints.NotBlank;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.linecorp.armeria.server.logging.AccessLogWriter;

@JsonTypeName("custom")
public class CustomAccessLogWriterFactory implements AccessLogWriterFactory {

    @NotBlank
    @Size(min = 2)
    @JsonProperty
    private String format;

    public CustomAccessLogWriterFactory() {
    }

    @Valid
    public static CustomAccessLogWriterFactory build(@NotBlank @Size(min = 2) String format) {
        CustomAccessLogWriterFactory factory = new CustomAccessLogWriterFactory();
        factory.format = format;
        return factory;
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(final String format) {
        this.format = format;
    }

    @Override
    public AccessLogWriter getWriter() {
        return AccessLogWriter.custom(this.format);
    }
}
