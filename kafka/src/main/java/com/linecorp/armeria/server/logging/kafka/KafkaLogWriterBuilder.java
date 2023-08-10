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

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.logging.LogFormatter;
import com.linecorp.armeria.common.logging.LogWriter;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestOnlyLog;

/**
 * Builds a new Kafka based {@link LogWriter}.
 */
@UnstableApi
@SuppressWarnings("unchecked")
public class KafkaLogWriterBuilder<K> {

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
    @Nullable
    private LogFormatter logFormatter;

    /**
     * Sets the {@link LogFormatter} which converts a {@link RequestOnlyLog} or {@link RequestLog}
     * into a log message. By default {@link LogFormatter#ofJson()} will be used.
     */
    public KafkaLogWriterBuilder<K> logFormatter(LogFormatter logFormatter) {
        this.logFormatter = requireNonNull(logFormatter, "logFormatter");
        return this;
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
        if (logFormatter == null) {
            logFormatter = LogFormatter.ofJson();
        }
        return new KafkaLogWriter<K>(producer, topic, requestLogKeyExtractor, responseLogKeyExtractor,
                                     logFormatter, loggingAtOnce, requestLogOutputPredicate,
                                     responseLogOutputPredicate);
    }
}
