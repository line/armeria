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
import static org.mockito.Mockito.verify;
import static org.mockito.internal.verification.VerificationModeFactory.times;

import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.internal.testing.GenerateNativeImageTrace;
import com.linecorp.armeria.server.ServiceRequestContext;

@GenerateNativeImageTrace
class KafkaAccessLogWriterTest {

    private static final String TOPIC_NAME = "topic-test";

    private static final RequestLog log;

    static {
        final ServiceRequestContext ctx =
                ServiceRequestContext.of(HttpRequest.of(
                        RequestHeaders.of(HttpMethod.GET, "/kyuto",
                                          HttpHeaderNames.AUTHORITY, "kawamuray")));
        ctx.logBuilder().endRequest();
        ctx.logBuilder().endResponse();

        log = ctx.log().ensureComplete();
    }

    @Mock
    private Producer<String, String> producer;

    @Captor
    private ArgumentCaptor<ProducerRecord<String, String>> captor;

    @Test
    void withoutKeyExtractor() {
        final KafkaAccessLogWriter<String, String> service =
                new KafkaAccessLogWriter<>(producer, TOPIC_NAME, log -> log.requestHeaders().authority());

        service.log(log);

        verify(producer, times(1)).send(captor.capture(), any(Callback.class));

        final ProducerRecord<String, String> record = captor.getValue();
        assertThat(record.key()).isNull();
        assertThat(record.value()).isEqualTo("kawamuray");
    }

    @Test
    void withKeyExtractor() {
        final KafkaAccessLogWriter<String, String> service =
                new KafkaAccessLogWriter<>(producer, TOPIC_NAME,
                                           log -> log.context().decodedPath(),
                                           log -> log.requestHeaders().authority());

        service.log(log);

        verify(producer, times(1)).send(captor.capture(), any(Callback.class));

        final ProducerRecord<String, String> record = captor.getValue();
        assertThat(record.key()).isEqualTo("/kyuto");
        assertThat(record.value()).isEqualTo("kawamuray");
    }

    @Test
    void closeProducerWhenRequested() {
        final KafkaAccessLogWriter<String, String> service =
                new KafkaAccessLogWriter<>(producer, TOPIC_NAME, log -> "");

        service.shutdown().join();
        verify(producer, times(1)).close();
    }
}
