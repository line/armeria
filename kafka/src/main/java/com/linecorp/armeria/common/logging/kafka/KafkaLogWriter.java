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

import static java.util.Objects.requireNonNull;

import java.util.function.Function;

import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.logging.LogFormatter;
import com.linecorp.armeria.common.logging.LogWriter;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestOnlyLog;

/**
 * A {@link LogWriter} that sends request and response logs to a Kafka backend.
 *
 * <p>This implementation uses {@link LogFormatter#ofJson()} by default to serialize logs
 * as JSON before sending to Kafka. You can customize the serialization by providing
 * custom key and value extractors.</p>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * Producer<String, String> producer = new KafkaProducer<>(props);
 * LogWriter logWriter = new KafkaLogWriter<>(producer, "armeria-logs");
 * 
 * Server server = Server.builder()
 *     .service("/", (ctx, req) -> HttpResponse.of("Hello!"))
 *     .decorator(LoggingService.builder()
 *         .logWriter(logWriter)
 *         .newDecorator())
 *     .build();
 * }</pre>
 *
 * @param <K> the type of the Kafka record key
 * @param <V> the type of the Kafka record value
 */
@UnstableApi
public final class KafkaLogWriter<K, V> implements LogWriter {

    private static final Logger logger = LoggerFactory.getLogger(KafkaLogWriter.class);

    private final Producer<K, V> producer;
    private final String topic;
    private final Function<? super RequestOnlyLog, ? extends @Nullable K> requestKeyExtractor;
    private final Function<? super RequestOnlyLog, ? extends @Nullable V> requestValueExtractor;
    private final Function<? super RequestLog, ? extends @Nullable K> responseKeyExtractor;
    private final Function<? super RequestLog, ? extends @Nullable V> responseValueExtractor;

    /**
     * Creates a new instance with JSON log formatting.
     *
     * @param producer a Kafka {@link Producer} which is used to send logs to Kafka
     * @param topic the name of topic which is used to send logs
     */
    @SuppressWarnings("unchecked")
    KafkaLogWriter(Producer<K, V> producer, String topic) {
        this(producer, topic,
             log -> null,
             log -> (V) LogFormatter.ofJson().formatRequest(log),
             log -> null,
             log -> (V) LogFormatter.ofJson().formatResponse(log));
    }

    /**
     * Creates a new instance with custom extractors.
     *
     * @param producer a Kafka {@link Producer} which is used to send logs to Kafka
     * @param topic the name of topic which is used to send logs
     * @param requestKeyExtractor a {@link Function} that extracts a {@code K}-typed record key from
     *                            a {@link RequestOnlyLog}. The {@link Function} is allowed to return
     *                            {@code null} to leave the record key unspecified.
     * @param requestValueExtractor a {@link Function} that extracts a {@code V}-typed record value from
     *                              a {@link RequestOnlyLog}. The {@link Function} is allowed to return
     *                              {@code null} to skip logging for the given log.
     * @param responseKeyExtractor a {@link Function} that extracts a {@code K}-typed record key from
     *                             a {@link RequestLog}. The {@link Function} is allowed to return
     *                             {@code null} to leave the record key unspecified.
     * @param responseValueExtractor a {@link Function} that extracts a {@code V}-typed record value from
     *                               a {@link RequestLog}. The {@link Function} is allowed to return
     *                               {@code null} to skip logging for the given log.
     */
    KafkaLogWriter(Producer<K, V> producer, String topic,
                          Function<? super RequestOnlyLog, ? extends @Nullable K> requestKeyExtractor,
                          Function<? super RequestOnlyLog, ? extends @Nullable V> requestValueExtractor,
                          Function<? super RequestLog, ? extends @Nullable K> responseKeyExtractor,
                          Function<? super RequestLog, ? extends @Nullable V> responseValueExtractor) {
        this.producer = requireNonNull(producer, "producer");
        this.topic = requireNonNull(topic, "topic");
        this.requestKeyExtractor = requireNonNull(requestKeyExtractor, "requestKeyExtractor");
        this.requestValueExtractor = requireNonNull(requestValueExtractor, "requestValueExtractor");
        this.responseKeyExtractor = requireNonNull(responseKeyExtractor, "responseKeyExtractor");
        this.responseValueExtractor = requireNonNull(responseValueExtractor, "responseValueExtractor");
    }

    @Override
    public void logRequest(RequestOnlyLog log) {
        requireNonNull(log, "log");
        final V value = requestValueExtractor.apply(log);
        if (value == null) {
            return;
        }

        final K key = requestKeyExtractor.apply(log);
        final ProducerRecord<K, V> record = new ProducerRecord<>(topic, key, value);
        producer.send(record, (metadata, exception) -> {
            if (exception != null) {
                logger.warn("Failed to send request log to Kafka: {}", record, exception);
            }
        });
    }

    @Override
    public void logResponse(RequestLog log) {
        requireNonNull(log, "log");
        final V value = responseValueExtractor.apply(log);
        if (value == null) {
            return;
        }

        final K key = responseKeyExtractor.apply(log);
        final ProducerRecord<K, V> record = new ProducerRecord<>(topic, key, value);
        producer.send(record, (metadata, exception) -> {
            if (exception != null) {
                logger.warn("Failed to send response log to Kafka: {}", record, exception);
            }
        });
    }

    /**
     * Returns a new {@link KafkaLogWriterBuilder} for building a {@link KafkaLogWriter}.
     *
     * @param producer a Kafka {@link Producer} which is used to send logs to Kafka
     * @param topic the name of topic which is used to send logs
     * @param <K> the type of the Kafka record key
     * @param <V> the type of the Kafka record value
     * @return a new {@link KafkaLogWriterBuilder}
     */
    public static <K, V> KafkaLogWriterBuilder<K, V> builder(Producer<K, V> producer, String topic) {
        return new KafkaLogWriterBuilder<>(producer, topic);
    }
}
