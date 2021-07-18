package example.armeria.server.sangria

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.client.logging.LoggingClient
import com.linecorp.armeria.common.{HttpRequest, HttpStatus, MediaType}
import com.linecorp.armeria.server.ServerBuilder
import com.linecorp.armeria.server.logging.LoggingService
import com.linecorp.armeria.testing.junit5.server.ServerExtension
import example.armeria.server.sangria.Main.configureService
import munit.FunSuite
import net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson

class GraphqlServiceSuite extends FunSuite {

  val server: ServerExtension = new ServerExtension() {
    override protected def configure(sb: ServerBuilder): Unit = {
      sb.decorator(LoggingService.newDecorator())
      configureService(sb)
    }
  }

  override def beforeAll(): Unit = {
    server.start()
  }

  override def afterAll(): Unit = {
    server.stop()
  }

  val queries = Map(
    """{user(id: "1") {name}}""" -> "hero",
    """{user(id: "2") {name}}""" -> "human",
    """{user(id: "3") {name}}""" -> "droid"
  )

  queries.foreach {
    case (query, result) =>
      test(s"should fetch user data by $query") {
        val client = WebClient
          .builder(server.httpUri())
          .decorator(LoggingClient.newDecorator())
          .build()
        val request = HttpRequest
          .builder()
          .post("/graphql")
          .content(MediaType.GRAPHQL, query)
          .build()
        val response = client.execute(request).aggregate.join()

        assertEquals(response.status, HttpStatus.OK)
        assertThatJson(response.contentUtf8).node("data.user.name").isEqualTo(result)
      }
  }
}
