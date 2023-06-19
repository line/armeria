/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.server.logging.kafka;

import static com.linecorp.armeria.common.logging.LogWriterBuilder.DEFAULT_REQUEST_LOG_LEVEL_MAPPER;
import static com.linecorp.armeria.common.logging.LogWriterBuilder.DEFAULT_RESPONSE_LOG_LEVEL_MAPPER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.internal.verification.VerificationModeFactory.times;

import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.logging.LogFormatter;
import com.linecorp.armeria.common.logging.LogWriter;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestOnlyLog;
import com.linecorp.armeria.server.ServiceRequestContext;

class KafkaLogWriterTest {

    private static final String TOPIC_NAME = "topic-test";
    private static final String REQUEST_REGEX =
            "^\\{\"type\":\"request\",\"startTime\":\".+\",\"length\":\".+\"," +
            "\"duration\":\".+\",\"scheme\":\".+\",\"name\":\".+\",\"headers\":\\{\".+\"}}$";
    private static final String RESPONSE_REGEX =
            "^\\{\"type\":\"response\",\"startTime\":\".+\",\"length\":\".+\"," +
            "\"duration\":\".+\",\"totalDuration\":\".+\",\"headers\":\\{\".+\"}}$";

    @Mock
    private Producer<String, String> producer;

    @Captor
    private ArgumentCaptor<ProducerRecord<String, String>> captor;

    @Test
    void logRequest() {
        final LogWriter kafkaLogWriter = KafkaLogWriter.<String>builder()
                                                       .producer(producer)
                                                       .topic(TOPIC_NAME)
                                                       .requestLogKeyExtractor(requestOnlyLog -> "request")
                                                       .responseLogKeyExtractor(requestLog -> "response")
                                                       .requestLogLevelMapper(DEFAULT_REQUEST_LOG_LEVEL_MAPPER)
                                                       .responseLogLevelMapper(DEFAULT_RESPONSE_LOG_LEVEL_MAPPER)
                                                       .logFormatter(LogFormatter.ofJson())
                                                       .build();
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/kafka"));
        ctx.logBuilder().endRequest();
        final RequestOnlyLog log = (RequestOnlyLog) ctx.log();

        kafkaLogWriter.logRequest(log);

        verify(producer, times(1)).send(captor.capture(), any(Callback.class));
        final ProducerRecord<String, String> record = captor.getValue();
        assertThat(record.key()).isEqualTo("request");
        assertThat(record.value()).matches(REQUEST_REGEX);
    }

    @Test
    void logResponse() {
        final LogWriter kafkaLogWriter = KafkaLogWriter.<String>builder()
                                                       .producer(producer)
                                                       .topic(TOPIC_NAME)
                                                       .requestLogKeyExtractor(requestOnlyLog -> "request")
                                                       .responseLogKeyExtractor(requestLog -> "response")
                                                       .requestLogLevelMapper(DEFAULT_REQUEST_LOG_LEVEL_MAPPER)
                                                       .responseLogLevelMapper(DEFAULT_RESPONSE_LOG_LEVEL_MAPPER)
                                                       .logFormatter(LogFormatter.ofJson())
                                                       .build();
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/kafka"));
        ctx.logBuilder().endResponse();
        final RequestLog log = (RequestLog) ctx.log();

        kafkaLogWriter.logResponse(log);

        verify(producer, times(1)).send(captor.capture(), any(Callback.class));
        final ProducerRecord<String, String> record = captor.getValue();
        assertThat(record.key()).isEqualTo("response");
        assertThat(record.value()).matches(RESPONSE_REGEX);
    }
}
