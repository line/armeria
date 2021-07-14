package example.armeria.server.graphql.kotlin

import com.expediagroup.graphql.client.jackson.GraphQLClientJacksonSerializer
import com.expediagroup.graphql.client.types.GraphQLClientRequest
import com.linecorp.armeria.server.ServerBuilder
import com.linecorp.armeria.testing.junit5.server.ServerExtension
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.reflect.KClass

class GraphqlServiceTest {

    companion object {

        @JvmField
        @RegisterExtension
        val server: ServerExtension = object : ServerExtension() {
            @Throws(Exception::class)
            override fun configure(sb: ServerBuilder) {
                configureService(sb)
            }
        }

        private fun client(): GraphqlArmeriaClient {
            return GraphqlArmeriaClient(
                    uri = server.httpUri().resolve("/graphql"),
                    serializer = GraphQLClientJacksonSerializer())
        }
    }

    @ParameterizedTest
    @CsvSource(
            "1,hero",
            "2,human",
            "3,droid"
    )
    fun testUserDataFetch(id: String, expected: String) {
        runBlocking {
            val response = client().execute(UserNameQuery(id))
            assertThat(response.data?.userById?.name).isEqualTo(expected)
        }
    }

    data class UserNameResult(val userById: User)

    class UserNameQuery(id: String) : GraphQLClientRequest<UserNameResult> {
        override val query: String = "query {userById(id: $id) {name}}"
        override val operationName: String? = null
        override fun responseType(): KClass<UserNameResult> = UserNameResult::class
    }
}
