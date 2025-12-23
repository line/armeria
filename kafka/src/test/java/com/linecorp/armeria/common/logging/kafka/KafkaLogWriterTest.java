/*
 * Copyright 2025 LINE Corporation
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
package com.linecorp.armeria.common.logging.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestOnlyLog;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * Tests for {@link KafkaLogWriter}.
 */
class KafkaLogWriterTest {

    private Producer<String, String> producer;
    private KafkaLogWriter<String, String> logWriter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        producer = mock(Producer.class);
        logWriter = new KafkaLogWriter<>(producer, "test-topic");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldSendRequestLogToKafka() {
        final ServiceRequestContext ctx = ServiceRequestContext.of(
                HttpRequest.of(HttpMethod.GET, "/test"));
        final RequestOnlyLog log = ctx.log().partial();

        logWriter.logRequest(log);

        final ArgumentCaptor<ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(producer).send(captor.capture(), any(Callback.class));

        final ProducerRecord<String, String> record = captor.getValue();
        assertThat(record.topic()).isEqualTo("test-topic");
        assertThat(record.value()).isNotNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldSendResponseLogToKafka() {
        final ServiceRequestContext ctx = ServiceRequestContext.of(
                HttpRequest.of(HttpMethod.GET, "/test"));
        ctx.logBuilder().endRequest();
        ctx.logBuilder().responseHeaders(HttpStatus.OK.toHttpHeaders());
        ctx.logBuilder().endResponse();
        final RequestLog log = ctx.log().ensureComplete();

        logWriter.logResponse(log);

        final ArgumentCaptor<ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(producer).send(captor.capture(), any(Callback.class));

        final ProducerRecord<String, String> record = captor.getValue();
        assertThat(record.topic()).isEqualTo("test-topic");
        assertThat(record.value()).isNotNull();
    }

    @Test
    void shouldSkipWhenValueExtractorReturnsNull() {
        final KafkaLogWriter<String, String> writer = KafkaLogWriter
                .<String, String>builder(producer, "test-topic")
                .requestValueExtractor(log -> null)
                .build();

        final ServiceRequestContext ctx = ServiceRequestContext.of(
                HttpRequest.of(HttpMethod.GET, "/test"));
        final RequestOnlyLog log = ctx.log().partial();

        writer.logRequest(log);

        verify(producer, never()).send(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldUseCustomKeyExtractor() {
        final KafkaLogWriter<String, String> writer = KafkaLogWriter
                .<String, String>builder(producer, "test-topic")
                .requestKeyExtractor(log -> "custom-key")
                .requestValueExtractor(log -> "custom-value")
                .build();

        final ServiceRequestContext ctx = ServiceRequestContext.of(
                HttpRequest.of(HttpMethod.GET, "/test"));
        final RequestOnlyLog log = ctx.log().partial();

        writer.logRequest(log);

        final ArgumentCaptor<ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(producer).send(captor.capture(), any(Callback.class));

        final ProducerRecord<String, String> record = captor.getValue();
        assertThat(record.key()).isEqualTo("custom-key");
        assertThat(record.value()).isEqualTo("custom-value");
    }
}
