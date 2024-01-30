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
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.common.util.ShutdownHooks;

/**
 * An implementation of {@link LogWriter} that sends request/response logs to a Kafka backend.
 */
@UnstableApi
public final class KafkaLogWriter<K> implements LogWriter {

    private static final Logger logger = LoggerFactory.getLogger(KafkaLogWriter.class);

    /**
     * Returns a newly-created {@link KafkaLogWriterBuilder}.
     */
    public static <K> KafkaLogWriterBuilder<K> builder() {
        return new KafkaLogWriterBuilder<>();
    }

    /**
     * Returns a newly-created {@link LogWriter} created from the specified {@link Producer} and the topic.
     */
    public static <K> LogWriter of(Producer<K, String> producer, String topic) {
        return KafkaLogWriter.<K>builder()
                             .producer(producer)
                             .topic(topic)
                             .build();
    }

    private final Producer<K, String> producer;
    private final String topic;
    private final Function<? super RequestOnlyLog, ? extends @Nullable K> requestLogKeyExtractor;
    private final Function<? super RequestLog, ? extends @Nullable K> responseLogKeyExtractor;
    private final LogFormatter logFormatter;
    private final boolean loggingAtOnce;
    private final KafkaRequestLogOutputPredicate requestLogOutputPredicate;
    private final KafkaResponseLogOutputPredicate responseLogOutputPredicate;

    /**
     * Creates a new instance.
     *
     * @param producer a Kafka {@link Producer} which is used to send logs to Kafka
     * @param topic the name of topic which is used to send logs
     * @param requestLogKeyExtractor a {@link Function} that extracts a {@code K}-typed record key from
     *                               a {@link RequestLog} when writing request log.
     *                               The {@link Function} is allowed to return {@code null}
     *                               to leave the record key unspecified.
     * @param responseLogKeyExtractor a {@link Function} that extracts a {@code V}-typed record value from
     *                                a {@link RequestLog} when writing response log.
     *                                The {@link Function} is allowed to return {@code null}
     *                                to skip logging for the given {@link RequestLog}.
     * @param logFormatter a {@link LogFormatter} which converts a {@link RequestOnlyLog} or {@link RequestLog}
     *                     into a log message
     * @param loggingAtOnce If true, the formatted log message that includes the request log and
     *                      the response log is sent to Kafka at once.
     * @param requestLogOutputPredicate A predicate that determines whether a request log should
     *                                  be written or not.
     * @param responseLogOutputPredicate A predicate that determines whether a response log should
     *                                   be written or not.
     */
    KafkaLogWriter(Producer<K, String> producer, String topic,
                   Function<? super RequestOnlyLog, ? extends @Nullable K> requestLogKeyExtractor,
                   Function<? super RequestLog, ? extends @Nullable K> responseLogKeyExtractor,
                   LogFormatter logFormatter, boolean loggingAtOnce,
                   KafkaRequestLogOutputPredicate requestLogOutputPredicate,
                   KafkaResponseLogOutputPredicate responseLogOutputPredicate) {
        this.producer = requireNonNull(producer, "producer");
        this.topic = requireNonNull(topic, "topic");
        this.requestLogKeyExtractor = requireNonNull(requestLogKeyExtractor, "requestLogKeyExtractor");
        this.responseLogKeyExtractor = requireNonNull(responseLogKeyExtractor, "responseLogKeyExtractor");
        this.logFormatter = requireNonNull(logFormatter, "logFormatter");
        this.loggingAtOnce = loggingAtOnce;
        this.requestLogOutputPredicate = requireNonNull(requestLogOutputPredicate,
                                                        "requestLogOutputPredicate");
        this.responseLogOutputPredicate = requireNonNull(responseLogOutputPredicate,
                                                         "responseLogOutputPredicate");
        ShutdownHooks.addClosingTask(producer, "producer for KafkaLogWriter");
    }

    @Override
    public void logRequest(RequestOnlyLog log) {
        requireNonNull(log, "log");
        if (!loggingAtOnce && requestLogOutputPredicate.test(log)) {
            try (SafeCloseable ignored = log.context().push()) {
                final K key = requestLogKeyExtractor.apply(log);
                final ProducerRecord<K, String> producerRecord =
                        new ProducerRecord<>(topic, key, logFormatter.formatRequest(log));
                producer.send(producerRecord, (metadata, exception) -> {
                    if (exception != null) {
                        logger.warn("Failed to send a record to Kafka: {}", producerRecord, exception);
                    }
                });
            }
        }
    }

    @Override
    public void logResponse(RequestLog log) {
        requireNonNull(log, "log");
        if (!loggingAtOnce && responseLogOutputPredicate.test(log)) {
            try (SafeCloseable ignored = log.context().push()) {
                final K key = responseLogKeyExtractor.apply(log);
                final ProducerRecord<K, String> producerRecord =
                        new ProducerRecord<>(topic, key, logFormatter.formatResponse(log));
                producer.send(producerRecord, (metadata, exception) -> {
                    if (exception != null) {
                        logger.warn("Failed to send a record to Kafka: {}", producerRecord, exception);
                    }
                });
            }
        }
    }

    @Override
    public void log(RequestLog log) {
        if (!loggingAtOnce) {
            return;
        }
        final boolean shouldLogRequest = requestLogOutputPredicate.test(log);
        final boolean shouldLogResponse = responseLogOutputPredicate.test(log);

        final String message;
        if (log.responseCause() != null || (shouldLogRequest && shouldLogResponse)) {
            message = logFormatter.format(log);
        } else if (shouldLogRequest) {
            message = logFormatter.formatRequest(log);
        } else if (shouldLogResponse) {
            message = logFormatter.formatResponse(log);
        } else {
            return;
        }
        try (SafeCloseable ignored = log.context().push()) {
            final K key = responseLogKeyExtractor.apply(log);
            final ProducerRecord<K, String> producerRecord =
                    new ProducerRecord<>(topic, key, message);
            producer.send(producerRecord, (metadata, exception) -> {
                if (exception != null) {
                    logger.warn("Failed to send a record to Kafka: {}", producerRecord, exception);
                }
            });
        }
    }
}
