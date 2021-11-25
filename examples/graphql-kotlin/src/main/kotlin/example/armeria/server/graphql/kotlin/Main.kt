package example.armeria.server.graphql.kotlin

import com.expediagroup.graphql.generator.SchemaGeneratorConfig
import com.expediagroup.graphql.generator.TopLevelObject
import com.expediagroup.graphql.generator.toSchema
import com.linecorp.armeria.server.Server
import com.linecorp.armeria.server.ServerBuilder
import com.linecorp.armeria.server.graphql.GraphqlService
import org.slf4j.LoggerFactory
import kotlin.concurrent.thread

fun main() {
    val server = newServer(8080)
    Runtime.getRuntime().addShutdownHook(
            thread(start = false) {
                server.stop().join()
                logger.info("Server has been stopped.")
            }
    )
    server.start().join()
}

/**
 * Returns a new {@link Server} instance configured with GraphQL HTTP services.
 *
 * @param port the port that the server is to be bound to
 */
private fun newServer(port: Int): Server {
    val sb = Server.builder()
    sb.http(port)
    configureService(sb)
    return sb.build()
}

fun configureService(sb: ServerBuilder) {
    sb.service("/graphql", GraphqlService.builder().schema(toSchema(
            config = SchemaGeneratorConfig(listOf("example.armeria.server.graphql.kotlin")),
            queries = listOf(TopLevelObject(UserQuery()))
    )).build())
}

data class User(val id: Int, val name: String)

class UserQuery {

    private val data = mapOf(
            1 to User(1, "hero"),
            2 to User(2, "human"),
            3 to User(3, "droid")
    )

    /**
     * Retrieves a [User] associated with the specified ID. This method is automatically mapped by
     * [com.expediagroup.graphql.generator.execution.FunctionDataFetcher].
     * See [Fetching Data][https://opensource.expediagroup.com/graphql-kotlin/docs/schema-generator/execution/fetching-data/]
     * for details.
     */
    fun userById(id: Int): User? {
        return data[id]
    }
}

private val logger = LoggerFactory.getLogger("Main")
