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
import org.slf4j.Logger;

import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.logging.AbstractLogWriterBuilder;
import com.linecorp.armeria.common.logging.LogFormatter;
import com.linecorp.armeria.common.logging.LogLevel;
import com.linecorp.armeria.common.logging.LogWriter;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogLevelMapper;
import com.linecorp.armeria.common.logging.RequestOnlyLog;
import com.linecorp.armeria.common.logging.ResponseLogLevelMapper;

/**
 * Builds a new Kafka based {@link LogWriter}.
 */
@UnstableApi
@SuppressWarnings("unchecked")
public class KafkaLogWriterBuilder<K> extends AbstractLogWriterBuilder {

    KafkaLogWriterBuilder() {}

    private Producer<K, String> producer;
    private String topic;
    private Function<? super RequestOnlyLog, ? extends @Nullable K> requestLogKeyExtractor =
            requestOnlyLog -> null;
    private Function<? super RequestLog, ? extends @Nullable K> responseLogKeyExtractor =
            requestLog -> null;
    private boolean loggingAtOnce = true;
    private KafkaRequestLogOutputPredicate requestLogOutputPredicate =
            KafkaRequestLogOutputPredicate.always();
    private KafkaResponseLogOutputPredicate responseLogOutputPredicate =
            KafkaResponseLogOutputPredicate.always();

    @Override
    public KafkaLogWriterBuilder<K> logger(Logger logger) {
        return (KafkaLogWriterBuilder<K>) super.logger(logger);
    }

    @Override
    public KafkaLogWriterBuilder<K> logger(String loggerName) {
        return (KafkaLogWriterBuilder<K>) super.logger(loggerName);
    }

    @Override
    public KafkaLogWriterBuilder<K> requestLogLevel(LogLevel requestLogLevel) {
        return (KafkaLogWriterBuilder<K>) super.requestLogLevel(requestLogLevel);
    }

    @Override
    public KafkaLogWriterBuilder<K> requestLogLevel(Class<? extends Throwable> clazz,
                                                    LogLevel requestLogLevel) {
        return (KafkaLogWriterBuilder<K>) super.requestLogLevel(clazz, requestLogLevel);
    }

    @Override
    public KafkaLogWriterBuilder<K> requestLogLevelMapper(RequestLogLevelMapper requestLogLevelMapper) {
        return (KafkaLogWriterBuilder<K>) super.requestLogLevelMapper(requestLogLevelMapper);
    }

    @Override
    public KafkaLogWriterBuilder<K> responseLogLevel(HttpStatus status, LogLevel logLevel) {
        return (KafkaLogWriterBuilder<K>) super.responseLogLevel(status, logLevel);
    }

    @Override
    public KafkaLogWriterBuilder<K> responseLogLevel(HttpStatusClass statusClass, LogLevel logLevel) {
        return (KafkaLogWriterBuilder<K>) super.responseLogLevel(statusClass, logLevel);
    }

    @Override
    public KafkaLogWriterBuilder<K> responseLogLevel(Class<? extends Throwable> clazz, LogLevel logLevel) {
        return (KafkaLogWriterBuilder<K>) super.responseLogLevel(clazz, logLevel);
    }

    @Override
    public KafkaLogWriterBuilder<K> successfulResponseLogLevel(LogLevel successfulResponseLogLevel) {
        return (KafkaLogWriterBuilder<K>) super.successfulResponseLogLevel(successfulResponseLogLevel);
    }

    @Override
    public KafkaLogWriterBuilder<K> failureResponseLogLevel(LogLevel failedResponseLogLevel) {
        return (KafkaLogWriterBuilder<K>) super.failureResponseLogLevel(failedResponseLogLevel);
    }

    @Override
    public KafkaLogWriterBuilder<K> responseLogLevelMapper(ResponseLogLevelMapper responseLogLevelMapper) {
        return (KafkaLogWriterBuilder<K>) super.responseLogLevelMapper(responseLogLevelMapper);
    }

    /**
     * Sets the {@link LogFormatter} which converts a {@link RequestOnlyLog} or {@link RequestLog}
     * into a log message. By default {@link LogFormatter#ofJson()} will be used.
     */
    @Override
    public KafkaLogWriterBuilder<K> logFormatter(LogFormatter logFormatter) {
        return (KafkaLogWriterBuilder<K>) super.logFormatter(logFormatter);
    }

    /**
     * Set the Kafka {@link Producer} which is used to send logs to Kafka.
     */
    public KafkaLogWriterBuilder<K> producer(Producer<K, String> producer) {
        this.producer = requireNonNull(producer, "producer");
        return this;
    }

    /**
     * Set the name of topic which is used to send logs.
     */
    public KafkaLogWriterBuilder<K> topic(String topic) {
        this.topic = requireNonNull(topic, "topic");
        return this;
    }

    /**
     * Set the {@link Function} that extracts a {@code K}-typed record key from a {@link RequestOnlyLog}
     * when writing request log.
     * The {@link Function} is allowed to return {@code null} to leave the record key unspecified.
     * If unset, the default function that returns {@code null} will be used.
     */
    public KafkaLogWriterBuilder<K> requestLogKeyExtractor(
            Function<? super RequestOnlyLog, ? extends @Nullable K> requestLogKeyExtractor) {
        this.requestLogKeyExtractor = requireNonNull(requestLogKeyExtractor, "requestLogKeyExtractor");
        return this;
    }

    /**
     * Set the {@link Function} that extracts a {@code K}-typed record key from a {@link RequestLog}
     * when writing response log.
     * The {@link Function} is allowed to return {@code null} to leave the record key unspecified.
     * If unset, the default function that returns {@code null} will be used.
     */
    public KafkaLogWriterBuilder<K> responseLogKeyExtractor(
            Function<? super RequestLog, ? extends @Nullable K> responseLogKeyExtractor) {
        this.responseLogKeyExtractor = requireNonNull(responseLogKeyExtractor,"responseLogKeyExtractor");
        return this;
    }

    /**
     * Set whether the formatted log message that includes the request log and the response log is sent to
     * Kafka at once, or not.
     * If unset, {@code true} is use by default
     */
    public KafkaLogWriterBuilder<K> loggingAtOnce(boolean loggingAtOnce) {
        this.loggingAtOnce = loggingAtOnce;
        return this;
    }

    /**
     * Set the {@link KafkaRequestLogOutputPredicate} to determines whether a response log should
     * be written or not.
     * If unset, {@link KafkaRequestLogOutputPredicate#always()} is used by default.
     */
    public KafkaLogWriterBuilder<K> requestLogOutputPredicate(
            KafkaRequestLogOutputPredicate requestLogOutputPredicate) {
        this.requestLogOutputPredicate = requireNonNull(requestLogOutputPredicate, "requestLogOutputPredicate");
        return this;
    }

    /**
     * Set the {@link KafkaResponseLogOutputPredicate} to determines whether a response log should
     * be written or not.
     * If unset, {@link KafkaResponseLogOutputPredicate#always()} is used by default.
     */
    public KafkaLogWriterBuilder<K> responseLogOutputPredicate(
            KafkaResponseLogOutputPredicate responseLogOutputPredicate) {
        this.responseLogOutputPredicate = requireNonNull(responseLogOutputPredicate,
                                                         "responseLogOutputPredicate");
        return this;
    }

    /**
     * Returns a newly-created {@link LogWriter} that sends request/response logs to a Kafka backend
     * based on the properties of this builder.
     */
    public LogWriter build() {
        requireNonNull(producer, "producer");
        requireNonNull(topic, "topic");
        LogFormatter logFormatter = logFormatter();
        if (logFormatter == null) {
            logFormatter = LogFormatter.ofJson();
        }
        return new KafkaLogWriter<K>(producer, topic, requestLogKeyExtractor, responseLogKeyExtractor,
                                     logFormatter, loggingAtOnce, requestLogOutputPredicate,
                                     responseLogOutputPredicate);
    }
}
