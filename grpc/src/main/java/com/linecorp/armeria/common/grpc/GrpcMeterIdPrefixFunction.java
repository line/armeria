/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.armeria.common.grpc;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.linecorp.armeria.internal.common.metric.DefaultMeterIdPrefixFunction.addActiveRequestPrefixTags;
import static com.linecorp.armeria.internal.common.metric.DefaultMeterIdPrefixFunction.addCompleteRequestPrefixTags;
import static java.util.Objects.requireNonNull;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.rpc.Code;

import com.linecorp.armeria.client.metric.MetricCollectingClient;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestOnlyLog;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;
import com.linecorp.armeria.internal.common.metric.DefaultMeterIdPrefixFunction;
import com.linecorp.armeria.internal.common.util.StringUtil;
import com.linecorp.armeria.server.metric.MetricCollectingService;

import io.grpc.Status;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;

/**
 * Creates a {@link MeterIdPrefix} from a {@link RequestLog}.
 * This adds the {@code grpc.status} tag upon the tags that {@link DefaultMeterIdPrefixFunction} appends.
 *
 * @see MetricCollectingClient
 * @see MetricCollectingService
 */
public final class GrpcMeterIdPrefixFunction implements MeterIdPrefixFunction {

    private static final Map<String, Tag> STATUS_TAGS =
            Arrays.stream(Status.Code.values())
                  .map(code -> StringUtil.toString(code.value()))
                  .collect(toImmutableMap(Function.identity(), code -> Tag.of("grpc.status", code)));

    private static final Tag OK_TAG = Tag.of("grpc.status", StringUtil.toString(Code.OK_VALUE));
    private static final Tag UNKNOWN_TAG = Tag.of("grpc.status", StringUtil.toString(Code.UNKNOWN_VALUE));

    /**
     * Returns a newly created {@link GrpcMeterIdPrefixFunction} with the specified {@code name}.
     */
    public static GrpcMeterIdPrefixFunction of(String name) {
        return new GrpcMeterIdPrefixFunction(name);
    }

    private final String name;

    private GrpcMeterIdPrefixFunction(String name) {
        this.name = requireNonNull(name, "name");

    }

    @Override
    public MeterIdPrefix activeRequestPrefix(MeterRegistry registry, RequestOnlyLog log) {
        /* hostname.pattern, method, service */
        final Builder<Tag> tagListBuilder = ImmutableList.builderWithExpectedSize(3);
        addActiveRequestPrefixTags(tagListBuilder, log);
        return new MeterIdPrefix(name, tagListBuilder.build());
    }

    @Override
    public MeterIdPrefix completeRequestPrefix(MeterRegistry registry, RequestLog log) {
        /* grpc.status, hostname.pattern, http.status, method, service */
        final Builder<Tag> tagListBuilder = ImmutableList.builderWithExpectedSize(4);
        addGrpcStatus(tagListBuilder, log);
        addCompleteRequestPrefixTags(tagListBuilder, log);
        return new MeterIdPrefix(name, tagListBuilder.build());
    }

    private static void addGrpcStatus(ImmutableList.Builder<Tag> tagListBuilder, RequestLog log) {
        String status = log.responseHeaders().get(GrpcHeaderNames.GRPC_STATUS);
        if (status != null) {
            tagListBuilder.add(statusTag(status));
            return;
        }

        status = log.responseTrailers().get(GrpcHeaderNames.GRPC_STATUS);
        if (status != null) {
            tagListBuilder.add(statusTag(status));
            return;
        }

        final HttpHeaders trailers = GrpcWebTrailers.get(log.context());
        if (trailers != null) {
            status = trailers.get(GrpcHeaderNames.GRPC_STATUS);
            if (status != null) {
                tagListBuilder.add(statusTag(status));
                return;
            }
        }

        tagListBuilder.add(UNKNOWN_TAG);
    }

    private static Tag statusTag(String status) {
        // Normally, most gRPC requests complete with 0 status.
        if ("0".equals(status)) {
            return OK_TAG;
        }

        final Tag cached = STATUS_TAGS.get(status);
        if (cached != null) {
            return cached;
        }

        return Tag.of("grpc.status", status);
    }
}
