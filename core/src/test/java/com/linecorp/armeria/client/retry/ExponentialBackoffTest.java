package com.linecorp.armeria.client.retry;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

public class ExponentialBackoffTest {

    @Test
    public void test() {
        Backoff backoff = new ExponentialBackoff(10, 120, 3.0);
        assertThat(backoff.nextIntervalMillis(0)).isEqualTo(10);
        assertThat(backoff.nextIntervalMillis(1)).isEqualTo(30);
        assertThat(backoff.nextIntervalMillis(2)).isEqualTo(90);
        assertThat(backoff.nextIntervalMillis(3)).isEqualTo(120);
    }

    @Test
    public void testOverflow() {
        Backoff backoff = new ExponentialBackoff(Long.MAX_VALUE / 3, Long.MAX_VALUE, 2);
        assertThat(backoff.nextIntervalMillis(0)).isEqualTo(Long.MAX_VALUE / 3);
        assertThat(backoff.nextIntervalMillis(1)).isEqualTo((long) (Long.MAX_VALUE / 3 * 2.0));
        assertThat(backoff.nextIntervalMillis(2)).isEqualTo(Long.MAX_VALUE);
    }
}