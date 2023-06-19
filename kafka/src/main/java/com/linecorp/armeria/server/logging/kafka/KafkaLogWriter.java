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

import static com.linecorp.armeria.common.logging.LogWriterBuilder.DEFAULT_REQUEST_LOG_LEVEL;
import static com.linecorp.armeria.common.logging.LogWriterBuilder.DEFAULT_RESPONSE_LOG_LEVEL_MAPPER;
import static java.util.Objects.requireNonNull;

import java.util.function.Function;

import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.logging.LogFormatter;
import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.common.logging.LogWriter;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogLevelMapper;
import com.linecorp.armeria.common.logging.RequestOnlyLog;
import com.linecorp.armeria.common.logging.ResponseLogLevelMapper;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.common.util.ShutdownHooks;

/**
 * An implementation of {@link LogWriter} that sends request/response logs to a Kafka backend
 */
@UnstableApi
public final class KafkaLogWriter<K> implements LogWriter {

    /**
     * Returns a newly-created {@link KafkaLogWriterBuilder<K>}
     */
    public static <K> KafkaLogWriterBuilder<K> builder() {
        return new KafkaLogWriterBuilder<>();
    }

    /**
     * Returns a newly-created {@link LogWriter} created from the specified {@link Producer} and the topic.
     */
    public static <K> LogWriter of(Producer<K, String> producer,String topic) {
        return KafkaLogWriter.<K>builder()
                             .producer(producer)
                             .topic(topic)
                             .build();
    }

    static final Logger defaultLogger = LoggerFactory.getLogger(KafkaLogWriter.class);

    private static boolean warnedNullRequestLogLevelMapper;
    private static boolean warnedNullResponseLogLevelMapper;

    private final Producer<K, String> producer;
    private final String topic;
    private final Logger logger;
    private final RequestLogLevelMapper requestLogLevelMapper;
    private final ResponseLogLevelMapper responseLogLevelMapper;
    private final Function<? super RequestOnlyLog, ? extends @Nullable K> requestLogKeyExtractor;
    private final Function<? super RequestLog, ? extends @Nullable K> responseLogKeyExtractor;
    private final LogFormatter logFormatter;

    /**
     * Creates a new instance.
     *
     * @param producer a Kafka {@link Producer} which is used to send logs to Kafka
     * @param topic the name of topic which is used to send logs
     * @param logger the {@link Logger} to use when logging
     * @param requestLogKeyExtractor a {@link Function} that extracts a {@code K}-typed record key from
     *                               a {@link RequestLog} when writing request log.
     *                               The {@link Function} is allowed to return {@code null}
     *                               to leave the record key unspecified.
     * @param responseLogKeyExtractor a {@link Function} that extracts a {@code V}-typed record value from
     *                                a {@link RequestLog} when writing response log.
     *                                The {@link Function} is allowed to return {@code null}
     *                                to skip logging for the given {@link RequestLog}.
     * @param requestLogLevelMapper a {@link RequestLogLevelMapper} to use when mapping the log level
     *                              of request logs.
     * @param responseLogLevelMapper a {@link ResponseLogLevelMapper} to use when mapping the log level
     *                               of response logs.
     * @param logFormatter a {@link LogFormatter} which converts a {@link RequestOnlyLog} or {@link RequestLog}
     *                     into a log message
     */
    KafkaLogWriter(Producer<K, String> producer, String topic, Logger logger,
                   Function<? super RequestOnlyLog, ? extends @Nullable K> requestLogKeyExtractor,
                   Function<? super RequestLog, ? extends @Nullable K> responseLogKeyExtractor,
                   RequestLogLevelMapper requestLogLevelMapper, ResponseLogLevelMapper responseLogLevelMapper,
                   LogFormatter logFormatter) {
        this.producer = requireNonNull(producer, "producer");
        this.topic = requireNonNull(topic, "topic");
        this.logger = requireNonNull(logger, "logger");
        this.requestLogKeyExtractor = requireNonNull(requestLogKeyExtractor, "requestLogKeyExtractor");
        this.responseLogKeyExtractor = requireNonNull(responseLogKeyExtractor, "responseLogKeyExtractor");
        this.requestLogLevelMapper = requireNonNull(requestLogLevelMapper, "requestLogLevelMapper");
        this.responseLogLevelMapper = requireNonNull(responseLogLevelMapper, "responseLogLevelMapper");
        this.logFormatter = requireNonNull(logFormatter, "logFormatter");
        ShutdownHooks.addClosingTask(producer, "producer for KafkaLogWriter");
    }

    @Override
    public void logRequest(RequestOnlyLog log) {
        requireNonNull(log, "log");
        LogLevel requestLogLevel = requestLogLevelMapper.apply(log);
        if (requestLogLevel == null) {
            if (!warnedNullRequestLogLevelMapper) {
                warnedNullRequestLogLevelMapper = true;
                logger.warn("requestLogLevelMapper.apply() returned null; using default log level");
            }
            requestLogLevel = DEFAULT_REQUEST_LOG_LEVEL;
        }
        if (requestLogLevel.isEnabled(logger)) {
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
        LogLevel responseLogLevel = responseLogLevelMapper.apply(log);
        if (responseLogLevel == null) {
            if (!warnedNullResponseLogLevelMapper) {
                warnedNullResponseLogLevelMapper = true;
                logger.warn("responseLogLevelMapper.apply() returned null; using default log level mapper");
            }
            responseLogLevel = DEFAULT_RESPONSE_LOG_LEVEL_MAPPER.apply(log);
            assert responseLogLevel != null;
        }
        if (responseLogLevel.isEnabled(logger)) {
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
}
