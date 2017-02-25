package com.linecorp.armeria.client.retry;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Random;

import org.junit.Test;

public class BackoffTest {
    @Test
    public void withoutDelay() throws Exception {
        Backoff backoff = Backoff.withoutDelay();
        assertThat(backoff.nextIntervalMillis(0)).isEqualTo(0);
        assertThat(backoff.nextIntervalMillis(1)).isEqualTo(0);
        assertThat(backoff.nextIntervalMillis(2)).isEqualTo(0);
    }

    @Test
    public void fixed() throws Exception {
        Backoff backoff = Backoff.fixed(100);
        assertThat(backoff.nextIntervalMillis(0)).isEqualTo(100);
        assertThat(backoff.nextIntervalMillis(1)).isEqualTo(100);
        assertThat(backoff.nextIntervalMillis(2)).isEqualTo(100);
    }

    @Test
    public void exponential() throws Exception {
        Backoff backoff = Backoff.exponential(10, 50);
        assertThat(backoff.nextIntervalMillis(0)).isEqualTo(10);
        assertThat(backoff.nextIntervalMillis(1)).isEqualTo(20);
        assertThat(backoff.nextIntervalMillis(2)).isEqualTo(40);
        assertThat(backoff.nextIntervalMillis(3)).isEqualTo(50);
        assertThat(backoff.nextIntervalMillis(4)).isEqualTo(50);

        backoff = Backoff.exponential(10, 120, 3.0);
        assertThat(backoff.nextIntervalMillis(0)).isEqualTo(10);
        assertThat(backoff.nextIntervalMillis(1)).isEqualTo(30);
        assertThat(backoff.nextIntervalMillis(2)).isEqualTo(90);
        assertThat(backoff.nextIntervalMillis(3)).isEqualTo(120);
        assertThat(backoff.nextIntervalMillis(4)).isEqualTo(120);
    }

    @Test
    public void withJitter() throws Exception {
        Random random = new Random(1);
        Backoff backoff = Backoff.fixed(100).withJitter(10, 20, () -> random);
        assertThat(backoff.nextIntervalMillis(0)).isEqualTo(116);
        assertThat(backoff.nextIntervalMillis(1)).isEqualTo(113);
        assertThat(backoff.nextIntervalMillis(2)).isEqualTo(118);
    }

    @Test
    public void withMaxAttempts() throws Exception {
        Backoff backoff = Backoff.fixed(100).withMaxAttempts(2);
        assertThat(backoff.nextIntervalMillis(0)).isEqualTo(100);
        assertThat(backoff.nextIntervalMillis(1)).isEqualTo(100);
        assertThat(backoff.nextIntervalMillis(2)).isEqualTo(-1);
        assertThat(backoff.nextIntervalMillis(3)).isEqualTo(-1);
    }
}