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
