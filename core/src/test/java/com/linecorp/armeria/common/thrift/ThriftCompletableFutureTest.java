package com.linecorp.armeria.common.thrift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import org.junit.Test;

public class ThriftCompletableFutureTest {

    @Test
    public void success() throws Exception {
        ThriftCompletableFuture<String> future = ThriftCompletableFuture.successful("success");
        assertThat(future.get()).isEqualTo("success");
    }

    @Test
    public void error() throws Exception {
        ThriftCompletableFuture<String> future = ThriftCompletableFuture.failed(new IllegalStateException());
        assertThat(catchThrowable(future::get)).hasCauseInstanceOf(IllegalStateException.class);
    }

}
