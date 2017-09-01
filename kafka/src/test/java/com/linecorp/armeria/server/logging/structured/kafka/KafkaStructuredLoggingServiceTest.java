/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.server.logging.structured.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.Service;

public class KafkaStructuredLoggingServiceTest {
    @Rule
    public final MockitoRule mockitoRule = MockitoJUnit.rule();

    private static final String TOPIC_NAME = "topic-test";

    private static final class SimpleStructuredLog {
        private final String name;

        private SimpleStructuredLog(String name) {
            this.name = name;
        }
    }

    private static class KafkaStructuredLoggingServiceExposed
            extends KafkaStructuredLoggingService<HttpRequest, HttpResponse, SimpleStructuredLog> {
        KafkaStructuredLoggingServiceExposed(
                Producer<byte[], SimpleStructuredLog> producer,
                KeySelector<SimpleStructuredLog> keySelector,
                boolean needToCloseProducer) {
            super(mock(Service.class), log -> null, producer, TOPIC_NAME, keySelector, needToCloseProducer);
        }
    }

    @Mock
    private Producer<byte[], SimpleStructuredLog> producer;

    @Captor
    private ArgumentCaptor<ProducerRecord<byte[], SimpleStructuredLog>> captor;

    @Test
    public void testServiceWithoutKeySelector() {
        KafkaStructuredLoggingServiceExposed service =
                new KafkaStructuredLoggingServiceExposed(producer, null, false);

        SimpleStructuredLog log = new SimpleStructuredLog("kawamuray");
        service.writeLog(null, log);

        verify(producer, times(1)).send(captor.capture(), any(Callback.class));

        ProducerRecord<byte[], SimpleStructuredLog> record = captor.getValue();
        assertThat(record.key()).isNull();
        assertThat(record.value()).isEqualTo(log);
    }

    @Test
    public void testWithKeySelector() {
        KafkaStructuredLoggingServiceExposed service = new KafkaStructuredLoggingServiceExposed(
                producer, (res, log) -> log.name.getBytes(), false);

        SimpleStructuredLog log = new SimpleStructuredLog("kawamuray");
        service.writeLog(null, log);

        verify(producer, times(1)).send(captor.capture(), any(Callback.class));

        ProducerRecord<byte[], SimpleStructuredLog> record = captor.getValue();
        assertThat(record.key()).isNotNull();
        assertThat(new String(record.key())).isEqualTo(log.name);
        assertThat(record.value()).isEqualTo(log);
    }

    @Test
    public void testCloseProducerWhenRequested() {
        KafkaStructuredLoggingServiceExposed service =
                new KafkaStructuredLoggingServiceExposed(producer, null, true);

        service.close();
        verify(producer, times(1)).close();
    }

    @Test
    public void testDoNotCloseProducerWhenNotRequested() {
        KafkaStructuredLoggingServiceExposed service =
                new KafkaStructuredLoggingServiceExposed(producer, null, false);

        service.close();
        verify(producer, times(0)).close();
    }
}
