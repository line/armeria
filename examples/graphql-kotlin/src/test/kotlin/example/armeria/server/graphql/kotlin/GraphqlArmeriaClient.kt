package example.armeria.server.graphql.kotlin

import com.expediagroup.graphql.client.GraphQLClient
import com.expediagroup.graphql.client.serializer.GraphQLClientSerializer
import com.expediagroup.graphql.client.serializer.defaultGraphQLSerializer
import com.expediagroup.graphql.client.types.GraphQLClientRequest
import com.expediagroup.graphql.client.types.GraphQLClientResponse
import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpRequestBuilder
import com.linecorp.armeria.common.MediaType
import kotlinx.coroutines.future.await
import java.net.URI

class GraphqlArmeriaClient(
    private val uri: URI,
    private val client: WebClient = WebClient.of(),
    private val serializer: GraphQLClientSerializer = defaultGraphQLSerializer()
) : GraphQLClient<HttpRequestBuilder> {

    override suspend fun <T : Any> execute(
        request: GraphQLClientRequest<T>,
        requestCustomizer: HttpRequestBuilder.() -> Unit
    ): GraphQLClientResponse<T> {
        val response = client.execute(
            HttpRequest.builder()
                .apply(requestCustomizer)
                .path(uri.toString())
                .method(HttpMethod.POST)
                .content(MediaType.JSON_UTF_8, serializer.serialize(request))
                .build()
        ).aggregate().await()
        return serializer.deserialize(response.contentUtf8(), request.responseType())
    }

    override suspend fun execute(
        requests: List<GraphQLClientRequest<*>>,
        requestCustomizer: HttpRequestBuilder.() -> Unit
    ): List<GraphQLClientResponse<*>> {
        val response = client.execute(
            HttpRequest.builder()
                .apply(requestCustomizer)
                .path(uri.toString())
                .method(HttpMethod.POST)
                .content(MediaType.JSON_UTF_8, serializer.serialize(requests))
                .build()
        ).aggregate().await()
        return serializer.deserialize(response.contentUtf8(), requests.map { it.responseType() })
    }
}
