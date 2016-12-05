package com.linecorp.armeria.common.thrift;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import org.junit.Test;

public class ThriftCallbackListenableFutureTest {

    @Test
    public void success() throws Exception {
        ThriftCallbackListenableFuture<String> future = new ThriftCallbackListenableFuture<>();
        future.onComplete("success");
        assertThat(future.get()).isEqualTo("success");
    }

    @Test
    public void error() throws Exception {
        ThriftCallbackListenableFuture<String> future = new ThriftCallbackListenableFuture<>();
        future.onError(new IllegalStateException());
        assertThat(catchThrowable(future::get)).hasCauseInstanceOf(IllegalStateException.class);
    }

}
