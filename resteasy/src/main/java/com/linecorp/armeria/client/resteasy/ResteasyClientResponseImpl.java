/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.client.resteasy;

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.io.InputStream;

import org.jboss.resteasy.client.jaxrs.internal.ClientConfiguration;
import org.jboss.resteasy.client.jaxrs.internal.ClientResponse;
import org.jboss.resteasy.core.Headers;
import org.jboss.resteasy.tracing.RESTEasyTracingLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpStatus;

/**
 * Implements {@link ClientResponse}.
 */
final class ResteasyClientResponseImpl extends ClientResponse {

    private static final Logger logger = LoggerFactory.getLogger(ResteasyClientResponseImpl.class);

    private InputStream is;

    ResteasyClientResponseImpl(ClientConfiguration configuration, HttpHeaders headers, InputStream is) {
        super(configuration, RESTEasyTracingLogger.empty());
        setHeaders(toReasteasyHeaders(headers));
        final HttpStatus status = HttpStatus.valueOf(requireNonNull(headers.get(HttpHeaderNames.STATUS),
                                                                    HttpHeaderNames.STATUS.toString()));
        setStatus(status.code());
        setReasonPhrase(status.reasonPhrase());
        this.is = is;
    }

    @Override
    protected InputStream getInputStream() {
        return is;
    }

    @Override
    protected void setInputStream(InputStream is) {
        this.is = requireNonNull(is, "is");
        //resetEntity(); //?
    }

    @Override
    public void releaseConnection() throws IOException {
        releaseConnection(false);
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    @Override
    public void releaseConnection(boolean consumeInputStream) throws IOException {
        try {
            if (consumeInputStream) {
                while (is.available() > 0) {
                    is.read();
                }
            }
            is.close();
        } catch (IOException e) {
            // Swallowing because other ClientHttpEngine implementations are swallowing as well.
            // What is better?  causing a potential leak with inputstream slowly or cause an unexpected
            // and unhandled io error and potentially cause the service go down?
            if (logger.isWarnEnabled()) {
                logger.warn("Exception while releasing the connection!", e);
            }
        }
    }

    private static Headers<String> toReasteasyHeaders(HttpHeaders headers) {
        final Headers<String> reasteasyHeaders = new Headers<>();
        headers.forEach((key, value) -> reasteasyHeaders.add(key.toString(), value));
        return reasteasyHeaders;
    }
}
