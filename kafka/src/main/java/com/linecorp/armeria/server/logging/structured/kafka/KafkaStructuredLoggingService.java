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

import static java.util.Objects.requireNonNull;

import java.util.Properties;
import java.util.concurrent.Future;
import java.util.function.Function;

import javax.annotation.Nullable;

import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.logging.structured.StructuredLogBuilder;
import com.linecorp.armeria.server.logging.structured.StructuredLoggingService;

/**
 * Kafka backend for service log logging.
 * This class enable Kafka as a service log logging backend.
 *
 * <p>This method returns immediately after the {@link Producer#send(ProducerRecord, Callback)} returns rather
 * than waiting for returned {@link Future} completes so logs which are written and are not yet flushed can
 * be lost if an application crashes in unclean way.
 *
 * <p>Refer variety of {@link #newDecorator} methods to see how to enable Kafka based structured logging.
 */
public class KafkaStructuredLoggingService<I extends Request, O extends Response, L>
        extends StructuredLoggingService<I, O, L> {
    private static final Logger logger = LoggerFactory.getLogger(KafkaStructuredLoggingService.class);

    /**
     * Implements "key" selector of Kafka based service log writer.
     * Kafka as a notion of the "key" which is used as a criteria to guarantee message ordering and
     * to achieve fine-distributed message partitioning.
     * Users need to implement this interface in order to select arbitrary key from the request context
     * or from the content included in a request.
     */
    @FunctionalInterface
    public interface KeySelector<E> {
        /**
         * Selects a key which should be associated toe the record given as {@code structuredLog}.
         *
         * @return A byte-array represented key or null
         */
        @Nullable
        byte[] selectKey(RequestLog log, E structuredLog);
    }

    /**
     * Creates a decorator which provides {@link StructuredLoggingService} with full set of arguments.
     *
     * @param producer a kafka {@link Producer} producer which is used to send logs to Kafka
     * @param topic a name of topic which is used to send logs
     * @param logBuilder an instance of {@link StructuredLogBuilder} which is used to construct a log entry
     * @param keySelector a {@link KeySelector} which is used to decide what key to use for the log
     * @param <I> the {@link Request} type
     * @param <O> the {@link Response} type
     * @param <L> the type of the structured log representation
     *
     * @return a service decorator which adds structured logging support integrated to Kafka
     */
    public static <I extends Request, O extends Response, L>
    Function<Service<I, O>, StructuredLoggingService<I, O, L>> newDecorator(
            Producer<byte[], L> producer, String topic,
            StructuredLogBuilder<L> logBuilder, KeySelector<L> keySelector) {
        return service -> new KafkaStructuredLoggingService<>(
                service, logBuilder, producer, topic, keySelector, false);
    }

    /**
     * Creates a decorator which provides {@link StructuredLoggingService} with defaulting key to null.
     *
     * @param producer a kafka {@link Producer} producer which is used to send logs to Kafka
     * @param topic a name of topic which is used to send logs
     * @param logBuilder an instance of {@link StructuredLogBuilder} which is used to construct a log entry
     * @param <I> the {@link Request} type
     * @param <O> the {@link Response} type
     * @param <L> the type of the structured log representation
     *
     * @return a service decorator which adds structured logging support integrated to Kafka
     */
    public static <I extends Request, O extends Response, L>
    Function<Service<I, O>, StructuredLoggingService<I, O, L>> newDecorator(
            Producer<byte[], L> producer, String topic,
            StructuredLogBuilder<L> logBuilder) {
        return newDecorator(producer, topic, logBuilder, null);
    }

    /**
     * Creates a decorator which provides {@link StructuredLoggingService} with default {@link Producer}.
     *
     * @param bootstrapServers a {@code bootstrap.servers} config to specify destination Kafka cluster
     * @param topic a name of topic which is used to send logs
     * @param logBuilder an instance of {@link StructuredLogBuilder} which is used to construct a log entry
     * @param keySelector a {@link KeySelector} which is used to decide what key to use for the log
     * @param <I> the {@link Request} type
     * @param <O> the {@link Response} type
     * @param <L> the type of the structured log representation
     *
     * @return a service decorator which adds structured logging support integrated to Kafka
     */
    public static <I extends Request, O extends Response, L>
    Function<Service<I, O>, StructuredLoggingService<I, O, L>> newDecorator(
            String bootstrapServers, String topic,
            StructuredLogBuilder<L> logBuilder, KeySelector<L> keySelector) {
        Producer<byte[], L> producer = new KafkaProducer<>(newDefaultConfig(bootstrapServers));
        return service -> new KafkaStructuredLoggingService<>(
                service, logBuilder, producer, topic, keySelector, true);
    }

    /**
     * Creates a decorator which provides {@link StructuredLoggingService} with default {@link Producer}
     * and defaulting key to null.
     *
     * @param bootstrapServers a {@code bootstrap.servers} config to specify destination Kafka cluster
     * @param topic a name of topic which is used to send logs
     * @param logBuilder an instance of {@link StructuredLogBuilder} which is used to construct a log entry
     * @param <I> the {@link Request} type
     * @param <O> the {@link Response} type
     * @param <L> the type of the structured log representation
     *
     * @return a service decorator which adds structured logging support integrated to Kafka
     */
    public static <I extends Request, O extends Response, L>
    Function<Service<I, O>, StructuredLoggingService<I, O, L>>
    newDecorator(String bootstrapServers, String topic, StructuredLogBuilder<L> logBuilder) {
        return newDecorator(bootstrapServers, topic, logBuilder, null);
    }

    private static Properties newDefaultConfig(String bootstrapServers) {
        Properties producerConfig = new Properties();

        producerConfig.setProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);

        // Configure some values to make it likely fit majority of usages.
        producerConfig.setProperty(ProducerConfig.CLIENT_ID_CONFIG,
                                   KafkaStructuredLoggingService.class.getSimpleName());
        producerConfig.setProperty(ProducerConfig.ACKS_CONFIG, "all");
        producerConfig.setProperty(ProducerConfig.RETRIES_CONFIG, "3");

        return producerConfig;
    }

    private final Producer<byte[], L> producer;
    private final String topic;
    private final KeySelector<L> keySelector;
    private final boolean needToCloseProducer;

    KafkaStructuredLoggingService(Service<I, O> delegate,
                                  StructuredLogBuilder<L> logBuilder,
                                  Producer<byte[], L> producer,
                                  String topic,
                                  @Nullable KeySelector<L> keySelector,
                                  boolean needToCloseProducer) {
        super(delegate, logBuilder);

        this.producer = requireNonNull(producer, "producer");
        this.topic = requireNonNull(topic, "topic");
        this.keySelector = keySelector == null ? (res, log) -> null : keySelector;
        this.needToCloseProducer = needToCloseProducer;
    }

    @Override
    protected void writeLog(RequestLog log, L structuredLog) {
        byte[] key = keySelector.selectKey(log, structuredLog);

        ProducerRecord<byte[], L> producerRecord = new ProducerRecord<>(topic, key, structuredLog);
        producer.send(producerRecord, (metadata, exception) -> {
            if (exception != null) {
                logger.warn("failed to send service log to Kafka {}", producerRecord, exception);
            }
        });
    }

    @Override
    protected void close() {
        if (needToCloseProducer) {
            producer.close();
        }
    }
}
