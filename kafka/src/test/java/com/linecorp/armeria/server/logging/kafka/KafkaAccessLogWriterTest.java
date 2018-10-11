/*
 * Copyright 2018 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.server.logging.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.internal.verification.VerificationModeFactory.times;

import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import com.linecorp.armeria.common.logging.RequestLog;

public class KafkaAccessLogWriterTest {
    @Rule
    public final MockitoRule mockitoRule = MockitoJUnit.rule();

    private static final String TOPIC_NAME = "topic-test";

    @Mock
    private Producer<String, String> producer;

    @Captor
    private ArgumentCaptor<ProducerRecord<String, String>> captor;

    @Test
    public void withoutKeyExtractor() {
        final RequestLog log = mock(RequestLog.class);
        when(log.authority()).thenReturn("kawamuray");

        final KafkaAccessLogWriter<String, String> service =
                new KafkaAccessLogWriter<>(producer, TOPIC_NAME, RequestLog::authority);

        service.log(log);

        verify(producer, times(1)).send(captor.capture(), any(Callback.class));

        final ProducerRecord<String, String> record = captor.getValue();
        assertThat(record.key()).isNull();
        assertThat(record.value()).isEqualTo("kawamuray");
    }

    @Test
    public void withKeyExtractor() {
        final RequestLog log = mock(RequestLog.class);
        when(log.authority()).thenReturn("kawamuray");
        when(log.decodedPath()).thenReturn("kyuto");

        final KafkaAccessLogWriter<String, String> service =
                new KafkaAccessLogWriter<>(producer, TOPIC_NAME,
                                           RequestLog::decodedPath, RequestLog::authority);

        service.log(log);

        verify(producer, times(1)).send(captor.capture(), any(Callback.class));

        final ProducerRecord<String, String> record = captor.getValue();
        assertThat(record.key()).isEqualTo("kyuto");
        assertThat(record.value()).isEqualTo("kawamuray");
    }

    @Test
    public void closeProducerWhenRequested() {
        final KafkaAccessLogWriter<String, String> service =
                new KafkaAccessLogWriter<>(producer, TOPIC_NAME, log -> "");

        service.shutdown().join();
        verify(producer, times(1)).close();
    }
}
