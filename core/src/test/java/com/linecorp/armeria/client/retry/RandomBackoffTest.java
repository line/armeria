package com.linecorp.armeria.client.retry;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Random;

import org.junit.Test;

public class RandomBackoffTest {
    @Test
    public void nextIntervalMillis() throws Exception {
        Random r = new Random(1);
        Backoff backoff = new RandomBackoff(10, 100, () -> r);
        assertThat(backoff.nextIntervalMillis(0)).isEqualTo(46);
        assertThat(backoff.nextIntervalMillis(0)).isEqualTo(13);
        assertThat(backoff.nextIntervalMillis(0)).isEqualTo(28);
        assertThat(backoff.nextIntervalMillis(0)).isEqualTo(40);
    }

}