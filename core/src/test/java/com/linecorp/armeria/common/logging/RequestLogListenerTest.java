/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.common.logging;

import static com.linecorp.armeria.common.logging.RequestLogProperty.ALL_COMPLETE;
import static com.linecorp.armeria.common.logging.RequestLogProperty.REQUEST_COMPLETE;
import static com.linecorp.armeria.common.logging.RequestLogProperty.REQUEST_CONTENT;
import static com.linecorp.armeria.common.logging.RequestLogProperty.REQUEST_CONTENT_PREVIEW;
import static com.linecorp.armeria.common.logging.RequestLogProperty.REQUEST_END_TIME;
import static com.linecorp.armeria.common.logging.RequestLogProperty.REQUEST_FIRST_BYTES_TRANSFERRED_TIME;
import static com.linecorp.armeria.common.logging.RequestLogProperty.REQUEST_HEADERS;
import static com.linecorp.armeria.common.logging.RequestLogProperty.REQUEST_LENGTH;
import static com.linecorp.armeria.common.logging.RequestLogProperty.REQUEST_START_TIME;
import static com.linecorp.armeria.common.logging.RequestLogProperty.RESPONSE_COMPLETE;
import static com.linecorp.armeria.common.logging.RequestLogProperty.RESPONSE_CONTENT;
import static com.linecorp.armeria.common.logging.RequestLogProperty.RESPONSE_END_TIME;
import static com.linecorp.armeria.common.logging.RequestLogProperty.RESPONSE_HEADERS;
import static com.linecorp.armeria.common.logging.RequestLogProperty.RESPONSE_START_TIME;
import static com.linecorp.armeria.common.logging.RequestLogProperty.SESSION;
import static com.linecorp.armeria.common.logging.RequestLogProperty.allProperties;
import static com.linecorp.armeria.common.logging.RequestLogProperty.requestProperties;
import static com.linecorp.armeria.common.logging.RequestLogProperty.responseProperties;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.server.ServiceRequestContext;

class RequestLogListenerTest {

    @Test
    void listenerNotifiedWhenPropertyBecomesAvailable() {
        final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final List<RequestLogProperty> notifiedProperties = new ArrayList<>();

        ctx.log().addListener((property, log) -> notifiedProperties.add(property));

        // The properties are set when the ctx is created.
        assertThat(notifiedProperties).containsExactly(REQUEST_START_TIME, SESSION, REQUEST_HEADERS);

        ctx.logBuilder().requestFirstBytesTransferred();
        assertThat(notifiedProperties).containsExactly(REQUEST_START_TIME, SESSION, REQUEST_HEADERS,
                                                       REQUEST_FIRST_BYTES_TRANSFERRED_TIME);
        // End request
        ctx.logBuilder().endRequest();
        assertThat(notifiedProperties).containsAll(requestProperties());
        notifiedProperties.clear();

        // Start response
        ctx.logBuilder().startResponse();
        assertThat(notifiedProperties).containsExactly(RESPONSE_START_TIME);

        // Add response headers
        ctx.logBuilder().responseHeaders(ResponseHeaders.of(HttpStatus.OK));
        assertThat(notifiedProperties).containsExactly(RESPONSE_START_TIME, RESPONSE_HEADERS);

        // End response
        ctx.logBuilder().endResponse();
        assertThat(notifiedProperties).containsAll(responseProperties());
        assertThat(notifiedProperties).contains(ALL_COMPLETE);
    }

    @Test
    void listenerNotifiedImmediatelyForAlreadyAvailableProperties() {
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final RequestLogAccess logAccess = ctx.log();

        ctx.logBuilder().endRequest();

        // Now add listener - it should be notified immediately for already available properties
        final List<RequestLogProperty> notifiedProperties = new ArrayList<>();
        logAccess.addListener((property, log) -> notifiedProperties.add(property));

        assertThat(notifiedProperties).containsExactlyElementsOf(requestProperties());
    }

    @Test
    void multipleListenersNotified() {
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final RequestLogAccess logAccess = ctx.log();

        final List<RequestLogProperty> listener1Events = new ArrayList<>();
        final List<RequestLogProperty> listener2Events = new ArrayList<>();

        logAccess.addListener((property, log) -> listener1Events.add(property));

        logAccess.addListener((property, log) -> listener2Events.add(property));

        ctx.logBuilder().endRequest();

        // Both listeners should be notified
        assertThat(listener1Events).isEqualTo(listener2Events);
        assertThat(listener1Events).containsExactlyInAnyOrderElementsOf(requestProperties());
    }

    @Test
    void listenerReceivesCorrectLog() {
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/test"));
        final RequestLogAccess logAccess = ctx.log();

        final List<RequestLog> receivedLogs = new ArrayList<>();

        logAccess.addListener((property, log) -> receivedLogs.add(log));

        ctx.logBuilder().endRequest();

        assertThat(receivedLogs.get(0)).isSameAs(ctx.log());
    }

    @Test
    void listenerNotifiedForRequestContentProperties() {
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final RequestLogAccess logAccess = ctx.log();
        final List<RequestLogProperty> notifiedProperties = new ArrayList<>();

        logAccess.addListener((property, log) -> notifiedProperties.add(property));

        assertThat(notifiedProperties).doesNotContain(REQUEST_CONTENT);
        final RpcRequest rpcRequest = RpcRequest.of(String.class, "hello");
        ctx.logBuilder().requestContent(rpcRequest, null);
        assertThat(notifiedProperties).contains(REQUEST_CONTENT);

        assertThat(notifiedProperties).doesNotContain(REQUEST_CONTENT_PREVIEW);
        ctx.logBuilder().requestContentPreview("preview");
        assertThat(notifiedProperties).contains(REQUEST_CONTENT_PREVIEW);
    }

    @Test
    void listenerExceptionDoesNotAffectOtherListeners() {
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final RequestLogAccess logAccess = ctx.log();

        final List<RequestLogProperty> goodListenerEvents = new ArrayList<>();

        // Add a listener that throws an exception
        logAccess.addListener((property, log) -> {
            throw new RuntimeException("Test exception from listener");
        });

        // Add a good listener
        logAccess.addListener((property, log) -> goodListenerEvents.add(property));

        ctx.logBuilder().endRequest();
        ctx.logBuilder().endResponse();

        // The good listener should still be notified despite the exception in the first listener
        assertThat(goodListenerEvents).containsExactlyInAnyOrderElementsOf(allProperties());
    }

    @Test
    void listenerNotifiedForDeferredProperties() {
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final RequestLogAccess logAccess = ctx.log();
        final List<RequestLogProperty> notifiedProperties = new ArrayList<>();

        logAccess.addListener((property, log) -> notifiedProperties.add(property));

        // Defer content properties
        ctx.logBuilder().defer(REQUEST_CONTENT);
        ctx.logBuilder().defer(RESPONSE_CONTENT);

        ctx.logBuilder().startRequest();
        ctx.logBuilder().endRequest();
        ctx.logBuilder().startResponse();
        ctx.logBuilder().endResponse();

        assertThat(notifiedProperties).contains(REQUEST_END_TIME);
        assertThat(notifiedProperties).doesNotContain(REQUEST_CONTENT);
        assertThat(notifiedProperties).doesNotContain(REQUEST_COMPLETE);

        assertThat(notifiedProperties).contains(RESPONSE_END_TIME);
        assertThat(notifiedProperties).doesNotContain(RESPONSE_CONTENT);
        assertThat(notifiedProperties).doesNotContain(RESPONSE_COMPLETE);
        assertThat(notifiedProperties).doesNotContain(ALL_COMPLETE);

        // Now set the deferred properties
        ctx.logBuilder().requestContent(null, null);
        assertThat(notifiedProperties).contains(REQUEST_CONTENT, REQUEST_COMPLETE);
        assertThat(notifiedProperties).doesNotContain(ALL_COMPLETE);

        ctx.logBuilder().responseContent(null, null);
        assertThat(notifiedProperties).contains(RESPONSE_CONTENT, RESPONSE_COMPLETE, ALL_COMPLETE);
    }

    @Test
    void listenerNotifiedForRequestLength() {
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final RequestLogAccess logAccess = ctx.log();
        final List<RequestLogProperty> notifiedProperties = new ArrayList<>();

        logAccess.addListener((property, log) -> {
            notifiedProperties.add(property);
            if (property == REQUEST_LENGTH) {
                assertThat(log.requestLength()).isEqualTo(100L);
            }
        });

        ctx.logBuilder().requestLength(100L);
        assertThat(notifiedProperties).contains(REQUEST_LENGTH);
    }
}
