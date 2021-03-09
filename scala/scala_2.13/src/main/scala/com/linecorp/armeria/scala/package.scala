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
package com.linecorp.armeria

import com.google.common.util.concurrent.MoreExecutors
import com.linecorp.armeria.common.annotation.UnstableApi

import _root_.scala.concurrent.ExecutionContext

/**
 * Provides a collection of utilities for using Armeria in a project written in Scala.
 * Read [[https://armeria.dev/docs/advanced-scala]] for more information.
 */
package object scala {

  /**
   * An `ExecutionContext` that runs the submitted `Runnable`s immediately on the thread that calls `execute()`
   * and then yields back control to the caller after the `Runnable` finishes its execution.
   *
   * This is often useful when you need to avoid unnecessary context switches for the short-running tasks or
   * asynchronous callbacks that are OK to run from an event loop thread.
   *
   * You must *not* use this `ExecutionContext` for a potentially long-running tasks.
   */
  @UnstableApi
  val directExecutionContext: ExecutionContext = {
    // Use ExecutionContext.parasitic which implements BatchingExecutor for Scala 2.13+.
    val companionObjectClass =
      Class.forName(s"${classOf[ExecutionContext].getName}$$", false, classOf[ExecutionContext].getClassLoader)

    try {
      companionObjectClass.getField("parasitic").asInstanceOf[ExecutionContext]
    } catch {
      // Build a new ExecutionContext from a direct executor, which should still work.
      case _: NoSuchFieldException =>
        ExecutionContext.fromExecutor(MoreExecutors.directExecutor())
    }
  }

  /**
   * Provides a collection of useful extension methods and implicit conversions for using Armeria
   * in a project written in Scala. Read [[https://armeria.dev/docs/advanced-scala]] for more information.
   */
  @UnstableApi
  object implicits extends CommonConversions with CollectionConverters with ServerConversions
}
