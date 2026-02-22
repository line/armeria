package server.blog

import com.linecorp.armeria.server.Server
import com.linecorp.armeria.server.docs.DocService
import org.slf4j.LoggerFactory

val logger = LoggerFactory.getLogger("BlogServer")

/**
 * https://armeria.dev/tutorials/rest/blog
 */
@main def start = {
  val server = newServer(8080)
  Runtime.getRuntime.addShutdownHook(Thread(
    () => {
      server.stop().join()
      logger.info("Server has been stopped")
    }
  ))

  server.start().join()
  logger.info("Server has been started. Serving dummy service at http://127.0.0.1:{}", server.activeLocalPort())
  logger.info("Server has been started. Serving DocService at http://127.0.0.1:{}/docs", server.activeLocalPort())
}

private val docService =
  DocService
    .builder()
    .exampleRequests(BlogService.getClass, "createBlogPost", "{\"title\":\"My first blog\", \"content\":\"Hello Armeria!\"}")
    .build()

private def newServer(port: Int): Server =
  Server
    .builder()
    .http(port)
    .annotatedService(BlogService())
    .serviceUnder("/docs", docService)
    .build()
