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

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.logging.LogFormatter;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestOnlyLog;

/**
 * A builder for creating a {@link KafkaLogWriter}.
 *
 * @param <K> the type of the Kafka record key
 * @param <V> the type of the Kafka record value
 */
@UnstableApi
public final class KafkaLogWriterBuilder<K, V> {

    private final Producer<K, V> producer;
    private final String topic;
    private Function<? super RequestOnlyLog, ? extends @Nullable K> requestKeyExtractor = log -> null;
    @SuppressWarnings("unchecked")
    private Function<? super RequestOnlyLog, ? extends @Nullable V> requestValueExtractor =
            log -> (V) LogFormatter.ofJson().formatRequest(log);
    private Function<? super RequestLog, ? extends @Nullable K> responseKeyExtractor = log -> null;
    @SuppressWarnings("unchecked")
    private Function<? super RequestLog, ? extends @Nullable V> responseValueExtractor =
            log -> (V) LogFormatter.ofJson().formatResponse(log);

    KafkaLogWriterBuilder(Producer<K, V> producer, String topic) {
        this.producer = requireNonNull(producer, "producer");
        this.topic = requireNonNull(topic, "topic");
    }

    /**
     * Sets the function that extracts a key from a {@link RequestOnlyLog}.
     *
     * @param requestKeyExtractor a {@link Function} that extracts a {@code K}-typed record key
     * @return this builder
     */
    public KafkaLogWriterBuilder<K, V> requestKeyExtractor(
            Function<? super RequestOnlyLog, ? extends @Nullable K> requestKeyExtractor) {
        this.requestKeyExtractor = requireNonNull(requestKeyExtractor, "requestKeyExtractor");
        return this;
    }

    /**
     * Sets the function that extracts a value from a {@link RequestOnlyLog}.
     *
     * @param requestValueExtractor a {@link Function} that extracts a {@code V}-typed record value
     * @return this builder
     */
    public KafkaLogWriterBuilder<K, V> requestValueExtractor(
            Function<? super RequestOnlyLog, ? extends @Nullable V> requestValueExtractor) {
        this.requestValueExtractor = requireNonNull(requestValueExtractor, "requestValueExtractor");
        return this;
    }

    /**
     * Sets the function that extracts a key from a {@link RequestLog}.
     *
     * @param responseKeyExtractor a {@link Function} that extracts a {@code K}-typed record key
     * @return this builder
     */
    public KafkaLogWriterBuilder<K, V> responseKeyExtractor(
            Function<? super RequestLog, ? extends @Nullable K> responseKeyExtractor) {
        this.responseKeyExtractor = requireNonNull(responseKeyExtractor, "responseKeyExtractor");
        return this;
    }

    /**
     * Sets the function that extracts a value from a {@link RequestLog}.
     *
     * @param responseValueExtractor a {@link Function} that extracts a {@code V}-typed record value
     * @return this builder
     */
    public KafkaLogWriterBuilder<K, V> responseValueExtractor(
            Function<? super RequestLog, ? extends @Nullable V> responseValueExtractor) {
        this.responseValueExtractor = requireNonNull(responseValueExtractor, "responseValueExtractor");
        return this;
    }

    /**
     * Builds a new {@link KafkaLogWriter} with the configured options.
     *
     * @return a new {@link KafkaLogWriter}
     */
    public KafkaLogWriter<K, V> build() {
        return new KafkaLogWriter<>(producer, topic,
                                    requestKeyExtractor, requestValueExtractor,
                                    responseKeyExtractor, responseValueExtractor);
    }
}
