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

import com.linecorp.armeria.common.annotation.UnstableApi

/**
 * Provides a collection of utilities for using Armeria in a project written in Scala.
 * Read [[https://armeria.dev/docs/advanced-scala]] for more information.
 */
package object scala {

  /**
   * Provides a collection of useful extension methods and implicit conversions for using Armeria
   * in a project written in Scala. Read [[https://armeria.dev/docs/advanced-scala]] for more information.
   */
  @UnstableApi
  object implicits extends CommonConversions with CollectionConverters with ServerConversions
}
