/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.xds.client.endpoint;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.Duration;

import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.client.endpoint.EndpointWeightTransition;
import com.linecorp.armeria.client.endpoint.WeightRampingUpStrategyBuilder;
import com.linecorp.armeria.common.annotation.Nullable;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.LbPolicy;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.SlowStartConfig;

final class EndpointUtil {

    private static final Logger logger = LoggerFactory.getLogger(EndpointUtil.class);

    static EndpointSelectionStrategy selectionStrategy(Cluster cluster) {
        final SlowStartConfig slowStartConfig = slowStartConfig(cluster);
        switch (cluster.getLbPolicy()) {
            case RANDOM:
                return EndpointSelectionStrategy.roundRobin();
            default:
                if (cluster.getLbPolicy() != LbPolicy.ROUND_ROBIN) {
                    logger.warn("The supported 'Cluster.LbPolicy' are ('RANDOM', `ROUND_ROBIN`) for now." +
                                " Falling back to 'ROUND_ROBIN'.");
                }
                if (slowStartConfig != null) {
                    return rampingUpSelectionStrategy(slowStartConfig);
                }
                return EndpointSelectionStrategy.weightedRoundRobin();
        }
    }

    @Nullable
    private static SlowStartConfig slowStartConfig(Cluster cluster) {
        if (cluster.getLbPolicy() == LbPolicy.ROUND_ROBIN) {
            if (cluster.hasRoundRobinLbConfig() && cluster.getRoundRobinLbConfig().hasSlowStartConfig()) {
                return cluster.getRoundRobinLbConfig().getSlowStartConfig();
            }
        } else if (cluster.getLbPolicy() == LbPolicy.LEAST_REQUEST) {
            if (cluster.hasLeastRequestLbConfig() && cluster.getLeastRequestLbConfig().hasSlowStartConfig()) {
                return cluster.getLeastRequestLbConfig().getSlowStartConfig();
            }
        }
        return null;
    }

    private static EndpointSelectionStrategy rampingUpSelectionStrategy(SlowStartConfig slowStartConfig) {
        final WeightRampingUpStrategyBuilder builder = EndpointSelectionStrategy.builderForRampingUp();
        if (slowStartConfig.hasSlowStartWindow()) {
            final Duration slowStartWindow = slowStartConfig.getSlowStartWindow();
            final long totalWindowMillis =
                    Instant.ofEpochSecond(slowStartWindow.getSeconds(), slowStartWindow.getNanos())
                           .toEpochMilli();
            if (totalWindowMillis > 0) {
                // just use 10 steps for now
                final long windowMillis = totalWindowMillis / 10;
                builder.rampingUpIntervalMillis(windowMillis);
                // set just in case windowMillis is smaller than the default taskWindow
                builder.rampingUpTaskWindowMillis(windowMillis / 5);
            }
        }

        if (slowStartConfig.hasAggression()) {
            final double aggression = slowStartConfig.getAggression().getDefaultValue();
            double minWeightPercent = 0.1;
            if (slowStartConfig.hasMinWeightPercent()) {
                minWeightPercent = slowStartConfig.getMinWeightPercent().getValue();
            }
            builder.transition(EndpointWeightTransition.aggression(aggression, minWeightPercent));
        }
        return builder.build();
    }

    private EndpointUtil() {}
}
