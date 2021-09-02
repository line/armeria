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

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.function.Function;

import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.server.logging.AccessLogWriter;

/**
 * An {@link AccessLogWriter} that sends access logs to a Kafka backend.
 *
 * <p>This method returns immediately after the {@link Producer#send(ProducerRecord, Callback)} returns rather
 * than waiting for returned {@link Future} completes so logs which are written and are not yet flushed can
 * be lost if an application crashes in unclean way.
 */
public final class KafkaAccessLogWriter<K, V> implements AccessLogWriter {

    private static final Logger logger = LoggerFactory.getLogger(KafkaAccessLogWriter.class);

    private final Producer<K, V> producer;
    private final String topic;
    private final Function<? super RequestLog, ? extends @Nullable K> keyExtractor;
    private final Function<? super RequestLog, ? extends @Nullable V> valueExtractor;

    /**
     * Creates a new instance.
     *
     * @param producer a Kafka {@link Producer} which is used to send logs to Kafka
     * @param topic the name of topic which is used to send logs
     * @param valueExtractor a {@link Function} that extracts a {@code V}-typed record value from
     *                       a {@link RequestLog}. The {@link Function} is allowed to return {@code null}
     *                       to skip logging for the given {@link RequestLog}.
     */
    public KafkaAccessLogWriter(Producer<K, V> producer, String topic,
                                Function<? super RequestLog, ? extends @Nullable V> valueExtractor) {
        this(producer, topic, log -> null, valueExtractor);
    }

    /**
     * Creates a new instance.
     *
     * @param producer a Kafka {@link Producer} which is used to send logs to Kafka
     * @param topic the name of topic which is used to send logs
     * @param keyExtractor a {@link Function} that extracts a {@code K}-typed record key from
     *                     a {@link RequestLog}. The {@link Function} is allowed to return {@code null}
     *                     to leave the record key unspecified.
     * @param valueExtractor a {@link Function} that extracts a {@code V}-typed record value from
     *                       a {@link RequestLog}. The {@link Function} is allowed to return {@code null}
     *                       to skip logging for the given {@link RequestLog}.
     */
    public KafkaAccessLogWriter(Producer<K, V> producer, String topic,
                                Function<? super RequestLog, ? extends @Nullable K> keyExtractor,
                                Function<? super RequestLog, ? extends @Nullable V> valueExtractor) {
        this.producer = requireNonNull(producer, "producer");
        this.topic = requireNonNull(topic, "topic");
        this.keyExtractor = requireNonNull(keyExtractor, "keyExtractor");
        this.valueExtractor = requireNonNull(valueExtractor, "valueExtractor");
    }

    @Override
    public void log(RequestLog log) {
        @Nullable
        final V value = valueExtractor.apply(log);
        if (value == null) {
            return;
        }

        @Nullable
        final K key = keyExtractor.apply(log);
        final ProducerRecord<K, V> producerRecord = new ProducerRecord<>(topic, key, value);
        producer.send(producerRecord, (metadata, exception) -> {
            if (exception != null) {
                logger.warn("Failed to send a record to Kafka: {}", producerRecord, exception);
            }
        });
    }

    @Override
    public CompletableFuture<Void> shutdown() {
        return CompletableFuture.runAsync(producer::close);
    }
}
