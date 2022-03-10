package com.linecorp.armeria.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ArmeriaOptionsProviderTest {

    @Test
    void overrideDefaultArmeriaOptionsProvider() {
        assertThat(Flags.useOpenSsl()).isEqualTo(false);
        assertThat(Flags.numCommonBlockingTaskThreads()).isEqualTo(100);
    }

    @Test
    void overrideDefaultFallback() {
        assertThat(Flags.defaultRequestTimeoutMillis())
                .isEqualTo(DefaultFlags.DEFAULT_REQUEST_TIMEOUT_MILLIS);
        assertThat(Flags.defaultBackoffSpec())
                .isEqualTo(DefaultFlags.DEFAULT_BACKOFF_SPEC);
    }
}
