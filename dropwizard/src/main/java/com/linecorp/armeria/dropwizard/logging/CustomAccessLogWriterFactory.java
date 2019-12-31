/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.armeria.dropwizard.logging;

import java.util.Objects;

import javax.validation.Valid;

import org.hibernate.validator.constraints.NotBlank;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;

import com.linecorp.armeria.server.logging.AccessLogWriter;

/**
 * Responsible for creating an instance of {@link AccessLogWriter#custom(String)}.
 */
@JsonTypeName("custom")
public class CustomAccessLogWriterFactory implements AccessLogWriterFactory {

    /**
     * A custom {@link AccessLogWriter} for Armeria which requires some valid format.
     *
     * @param format - A non-empty logging format supported by Armeria
     */
    public static @Valid CustomAccessLogWriterFactory build(@NotBlank String format) {
        final CustomAccessLogWriterFactory factory = new CustomAccessLogWriterFactory();
        factory.format = format;
        Objects.requireNonNull(factory.getWriter()); // force the validation of the writer format
        return factory;
    }

    @NotBlank
    @JsonProperty
    private String format;

    /**
     * Returns the access log format string.
     */
    public String getFormat() {
        return format;
    }

    /**
     * Sets the access log format string.
     */
    public void setFormat(String format) {
        this.format = format;
    }

    @Override
    public AccessLogWriter getWriter() {
        return AccessLogWriter.custom(format);
    }
}
