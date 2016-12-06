package com.linecorp.armeria.common.thrift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import org.junit.Test;

public class ThriftListenableFutureTest {

    @Test
    public void success() throws Exception {
        ThriftListenableFuture<String> future = ThriftListenableFuture.successful("success");
        assertThat(future.get()).isEqualTo("success");
    }

    @Test
    public void error() throws Exception {
        ThriftListenableFuture<String> future = ThriftListenableFuture.failed(new IllegalStateException());
        assertThat(catchThrowable(future::get)).hasCauseInstanceOf(IllegalStateException.class);
    }

}
