/*
 *  Copyright 2017 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.common.metric;

import static com.google.common.base.Preconditions.checkArgument;
import static com.linecorp.armeria.common.metric.MetricKey.validateLabel;
import static com.linecorp.armeria.common.metric.MetricKey.validateNamePart;
import static com.linecorp.armeria.common.metric.MetricKey.validateNameParts;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.metric.MetricCollectingClient;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.server.PathMapping;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.metric.MetricCollectingService;

/**
 * Creates a {@link MetricKey} from a {@link RequestLog}.
 *
 * @see MetricCollectingClient
 * @see MetricCollectingService
 */
@FunctionalInterface
public interface MetricKeyFunction {

    /**
     * Returns the default function that creates a {@link MetricKey} with the specified name parts and
     * the labels derived from the current {@link PathMapping} (if available) and HTTP (or RPC) method name.
     * e.g.
     * <ul>
     *   <li>Name: {@code ["<namePart[0]>", ... "<namePart[n-1]"]}</li>
     *   <li>Labels: {@code {"pathMapping=exact:/service/path", "method=POST"}}</li>
     * </ul>
     */
    static MetricKeyFunction ofDefault(String... nameParts) {
        final List<String> namePartsCopy =
                validateNameParts(ImmutableList.copyOf(requireNonNull(nameParts, "nameParts")));

        return log -> {
            final RequestContext ctx = log.context();
            final Object requestEnvelope = log.requestHeaders();
            final Object requestContent = log.requestContent();

            String methodName = null;

            if (requestEnvelope instanceof HttpHeaders) {
                methodName = ((HttpHeaders) requestEnvelope).method().name();
            }

            if (requestContent instanceof RpcRequest) {
                methodName = ((RpcRequest) requestContent).method();
            }

            if (methodName == null) {
                methodName = MoreObjects.firstNonNull(log.method().name(), "__UNKNOWN_METHOD__");
            }

            if (ctx instanceof ServiceRequestContext) {
                final ServiceRequestContext sCtx = (ServiceRequestContext) ctx;
                final String pathMapping = String.join(",", sCtx.pathMapping().metricName());
                return new MetricKey(namePartsCopy,
                                     ImmutableMap.of(BuiltInMetricLabel.method, methodName,
                                                     BuiltInMetricLabel.pathMapping, pathMapping));
            } else {
                return new MetricKey(namePartsCopy,
                                     ImmutableMap.of(BuiltInMetricLabel.method, methodName));
            }
        };
    }

    /**
     * Returns the default function that creates a label-less {@link MetricKey} whose name starts with
     * the specified name parts and ends with the current {@link PathMapping#metricName()} (if available) and
     * HTTP (or RPC) method name.
     * e.g. {@code ["<namePart[0]>", ... "<namePart[n-1]", "exact:/service/path", "POST"]}
     *
     * @param nameParts the prefix of the created {@link MetricKey}
     */
    static MetricKeyFunction ofLabellessDefault(String... nameParts) {
        final List<String> namePartsCopy =
                validateNameParts(ImmutableList.copyOf(requireNonNull(nameParts, "nameParts")));

        return log -> {
            final RequestContext ctx = log.context();
            final Object requestEnvelope = log.requestHeaders();
            final Object requestContent = log.requestContent();

            String methodName = null;

            if (requestEnvelope instanceof HttpHeaders) {
                methodName = ((HttpHeaders) requestEnvelope).method().name();
            }

            if (requestContent instanceof RpcRequest) {
                methodName = ((RpcRequest) requestContent).method();
            }

            if (methodName == null) {
                methodName = MoreObjects.firstNonNull(log.method().name(), "__UNKNOWN_METHOD__");
            }

            final ImmutableList.Builder<String> builder = ImmutableList.builder();
            builder.addAll(namePartsCopy);

            if (ctx instanceof ServiceRequestContext) {
                final ServiceRequestContext sCtx = (ServiceRequestContext) ctx;
                builder.addAll(sCtx.pathMapping().metricName());
            }

            builder.add(methodName);
            return new MetricKey(builder.build());
        };
    }

    /**
     * Creates a {@link MetricKey} from the specified {@link RequestLog}.
     */
    MetricKey apply(RequestLog log);

    /**
     * Returns a {@link MetricKeyFunction} that returns a newly created {@link MetricKey} whose name is
     * prepended by the specified name part.
     */
    default MetricKeyFunction prepend(String namePart) {
        validateNamePart(namePart);
        return log -> apply(log).prepend(namePart);
    }

    /**
     * Returns a {@link MetricKeyFunction} that returns a newly created {@link MetricKey} which has
     * the specified label added.
     */
    default MetricKeyFunction withLabel(String label, String value) {
        validateLabel(label, value);
        return log -> apply(log).withLabel(label, value);
    }

    /**
     * Returns a {@link MetricKeyFunction} that returns a newly created {@link MetricKey} which has
     * the specified label added.
     */
    default MetricKeyFunction withLabel(MetricLabel label, String value) {
        validateLabel(label, value);
        return log -> apply(log).withLabel(label, value);
    }

    /**
     * Returns a {@link MetricKeyFunction} that returns a newly created {@link MetricKey} which has
     * the specified labels added.
     */
    default MetricKeyFunction withLabels(Map<?, String> labels) {
        requireNonNull(labels, "labels");

        final Map<?, String> labelsCopy = ImmutableMap.copyOf(labels);
        labelsCopy.keySet().forEach(k -> {
            if (k instanceof MetricLabel) {
                checkArgument(!((MetricLabel) k).name().isEmpty(),
                              "labels contains a label with an empty name: %s", labelsCopy);
            } else if (k instanceof CharSequence) {
                checkArgument(((CharSequence) k).length() > 0,
                              "labels contains a label with an empty name: %s", labelsCopy);
            } else {
                throw new IllegalArgumentException(
                        "labels contains a key of unsupported type: " + labelsCopy +
                        " (expected: " + MetricLabel.class.getSimpleName() +
                        " or " + CharSequence.class.getSimpleName() + ')');
            }
        });

        return log -> apply(log).withLabels(labelsCopy);
    }

    /**
     * Returns a {@link MetricKeyFunction} that applies transformation on the {@link MetricKey} returned by
     * this function.
     */
    default MetricKeyFunction andThen(Function<MetricKey, MetricKey> function) {
        return log -> function.apply(apply(log));
    }
}
