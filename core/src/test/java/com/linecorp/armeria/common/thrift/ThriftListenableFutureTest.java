/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

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
