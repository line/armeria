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

package example.armeria.server.sangria

import com.linecorp.armeria.server.file.HttpFile
import com.linecorp.armeria.server.sangria.SangriaGraphqlService
import com.linecorp.armeria.server.{Server, ServerBuilder}
import org.slf4j.LoggerFactory

object Main {
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    val server = newServer(8080)
    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      server.stop.join()
      logger.info("Server has been stopped.")
    }))
    server.start.join()
  }

  /**
   * Returns a new `Server` instance configured with GraphQL HTTP services.
   *
   * @param port the port that the server is to be bound to
   */
  private def newServer(port: Int) = {
    val sb = Server.builder()
    sb.http(port)
    configureService(sb)
    sb.build()
  }

  private[sangria] def configureService(sb: ServerBuilder): Unit = {
    sb.service("/graphql", SangriaGraphqlService(Users.UserSchema, new UserRepo))

    // TODO(ikhoon): Automatically serve GraphQL playground(https://github.com/graphql/graphql-playground)
    //               by Documentation service when an `AbstractGraphqlService` is bound to a server.

    // Browsing and invoking GraphQL services using GraphQL Playground.
    val service = HttpFile
      .of(getClass.getClassLoader, "/graphql-playground.html")
      .asService()
    sb.service("/graphql/playground", service)
  }
}
