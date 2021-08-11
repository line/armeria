/*
 * Copyright 2021 LINE Corporation
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
package com.linecorp.armeria.scala

import com.linecorp.armeria.common.annotation.UnstableApi

import _root_.scala.concurrent.ExecutionContextExecutor

/**
 * Provides the `ExecutionContext` implementations that could be useful when writing an Armeria application
 * with Scala.
 */
@UnstableApi
object ExecutionContexts {

  /**
   * An `ExecutionContextExecutor` that runs the submitted `Runnable`s immediately on the thread that called
   * `execute()` and then yields the control back to the caller after the `Runnable` finishes its execution.
   * If the `Runnable` throws an exception, it is propagated to the caller as-is.
   *
   * This is often useful when you need to avoid unnecessary context switches for the short-running tasks or
   * asynchronous callbacks that are OK to run from an event loop thread.
   *
   * You must *not* use this for a long-running or recursive task that could block an event loop thread or
   * cause a `StackOverflowError`.
   */
  val sameThread: ExecutionContextExecutor = new ExecutionContextExecutor {

    /**
     * Invokes the `run()` method of the given `Runnable`.
     */
    override def execute(runnable: Runnable): Unit = runnable.run()

    /**
     * Throws the given `Throwable` as-is.
     */
    override def reportFailure(cause: Throwable): Unit = throw cause
  }
}
