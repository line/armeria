package com.linecorp.armeria.common.metric;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.internal.common.metric.DistributionStatisticConfigUtil;

import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;

class AbstractMetricCollectingBuilderTest {
    @Test
    void testDefaultDistributionStatisticConfig() {
        final AbstractMetricCollectingBuilder abstractMetricCollectingBuilder =
                new AbstractMetricCollectingBuilder(MeterIdPrefixFunction.ofDefault("test")) {};

        assertThat(abstractMetricCollectingBuilder.distributionStatisticConfig())
                .isEqualTo(DistributionStatisticConfigUtil.DEFAULT_DIST_STAT_CFG);
    }

    @Test
    void testDistributionStatisticConfigSetter() {
        final AbstractMetricCollectingBuilder abstractMetricCollectingBuilder =
                new AbstractMetricCollectingBuilder(MeterIdPrefixFunction.ofDefault("test")) {};

        final DistributionStatisticConfig distConfig = DistributionStatisticConfig.builder().build();

        abstractMetricCollectingBuilder.distributionStatisticConfig(distConfig);

        assertThat(abstractMetricCollectingBuilder.distributionStatisticConfig())
                .isEqualTo(distConfig);
    }
}