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
 * under the License
 */

package com.linecorp.armeria.server.scalapb

import com.linecorp.armeria.client.WebClientBuilder
import com.linecorp.armeria.client.websocket.WebSocketClientBuilder
import com.linecorp.armeria.internal.testing.ServerRuleDelegate
import com.linecorp.armeria.server.ServerBuilder
import munit.Suite

trait ServerSuite {
  self: Suite =>

  private var delegate: ServerRuleDelegate = _

  protected def configureServer: ServerBuilder => Unit

  protected def configureWebClient: WebClientBuilder => Unit = _ => ()

  protected def configureWebSocketClient: WebSocketClientBuilder => Unit = _ => ()

  protected def server: ServerRuleDelegate = delegate

  /**
   * Returns whether this extension should run around each test method instead of the entire test class.
   * Implementations should override this method to return `true` to run around each test method.
   */
  protected def runServerForEachTest = false

  override def beforeAll(): Unit = {
    delegate = new ServerRuleDelegate(false) {
      override def configure(sb: ServerBuilder): Unit = configureServer(sb)

      override def configureWebClient(wcb: WebClientBuilder): Unit = self.configureWebClient(wcb)

      override def configureWebSocketClient(webSocketClientBuilder: WebSocketClientBuilder): Unit =
        self.configureWebSocketClient(webSocketClientBuilder)
    }

    if (!runServerForEachTest) {
      server.start()
    }
  }

  override def afterAll(): Unit = {
    if (!runServerForEachTest) {
      server.stop()
    }
  }

  override def beforeEach(context: BeforeEach): Unit = {
    if (runServerForEachTest) {
      server.start()
    }
  }

  override def afterEach(context: AfterEach): Unit = {
    if (runServerForEachTest) {
      server.stop()
    }
  }
}
