"use strict";(self.webpackChunkarmeria_site=self.webpackChunkarmeria_site||[]).push([[4360],{84672:function(e,t,r){r.r(t),r.d(t,{_frontmatter:function(){return l},default:function(){return v},pageTitle:function(){return c}});var a=r(63366),i=(r(67294),r(64983)),n=r(28819),o=["components"],c="Production checklist",l={},p=function(e){return function(t){return console.warn("Component "+e+" was not imported, exported, or provided by MDXProvider as global scope"),(0,i.kt)("div",t)}},s=p("Warning"),m=p("Tip"),d={pageTitle:c,_frontmatter:l},u=n.Z;function v(e){var t=e.components,r=(0,a.Z)(e,o);return(0,i.kt)(u,Object.assign({},d,r,{components:t,mdxType:"MDXLayout"}),(0,i.kt)("h1",{id:"production-checklist",style:{position:"relative"}},(0,i.kt)("a",{parentName:"h1",href:"#production-checklist","aria-label":"production checklist permalink",className:"anchor before"},(0,i.kt)("svg",{parentName:"a","aria-hidden":"true",focusable:"false",height:"16",version:"1.1",viewBox:"0 0 16 16",width:"16"},(0,i.kt)("path",{parentName:"svg",fillRule:"evenodd",d:"M4 9h1v1H4c-1.5 0-3-1.69-3-3.5S2.55 3 4 3h4c1.45 0 3 1.69 3 3.5 0 1.41-.91 2.72-2 3.25V8.59c.58-.45 1-1.27 1-2.09C10 5.22 8.98 4 8 4H4c-.98 0-2 1.22-2 2.5S3 9 4 9zm9-3h-1v1h1c1 0 2 1.22 2 2.5S13.98 12 13 12H9c-.98 0-2-1.22-2-2.5 0-.83.42-1.64 1-2.09V6.25c-1.09.53-2 1.84-2 3.25C6 11.31 7.55 13 9 13h4c1.45 0 3-1.69 3-3.5S14.5 6 13 6z"}))),"Production checklist"),(0,i.kt)(s,{mdxType:"Warning"},(0,i.kt)("p",null,"Note that the advice on this page is not always applicable for every use case and thus should be\napplied with caution. Do not apply the changes you really do not need.")),(0,i.kt)("p",null,"You may want to consider the following options before putting your Armeria application into production."),(0,i.kt)("ul",null,(0,i.kt)("li",{parentName:"ul"},(0,i.kt)("p",{parentName:"li"},"Specify the maximum number of accepted connections. The default is ",(0,i.kt)("em",{parentName:"p"},"unbounded"),"."),(0,i.kt)("pre",{parentName:"li"},(0,i.kt)("code",{parentName:"pre",className:"language-java"},"import com.linecorp.armeria.server.Server;\nimport com.linecorp.armeria.server.ServerBuilder;\n\nServerBuilder sb = Server.builder();\nsb.maxNumConnections(500);\n"))),(0,i.kt)("li",{parentName:"ul"},(0,i.kt)("p",{parentName:"li"},"Specify an alternative ",(0,i.kt)("inlineCode",{parentName:"p"},"blockingTaskExecutor")," based on expected workload if your server has\na service that uses it, such as ",(0,i.kt)("a",{parentName:"p",href:"type://TomcatService:https://javadoc.io/doc/com.linecorp.armeria/armeria-javadoc/latest/com/linecorp/armeria/server/tomcat/TomcatService.html"},"type://TomcatService"),", ",(0,i.kt)("a",{parentName:"p",href:"type://JettyService:https://javadoc.io/doc/com.linecorp.armeria/armeria-javadoc/latest/com/linecorp/armeria/server/jetty/JettyService.html"},"type://JettyService")," and ",(0,i.kt)("a",{parentName:"p",href:"type://THttpService:https://javadoc.io/doc/com.linecorp.armeria/armeria-javadoc/latest/com/linecorp/armeria/server/thrift/THttpService.html"},"type://THttpService")," with\nsynchronous service implementation. The default is a simple ",(0,i.kt)("inlineCode",{parentName:"p"},"ScheduledThreadPoolExecutor")," with maximum\n200 threads, provided by ",(0,i.kt)("a",{parentName:"p",href:"type://CommonPools:https://javadoc.io/doc/com.linecorp.armeria/armeria-javadoc/latest/com/linecorp/armeria/common/CommonPools.html"},"type://CommonPools"),"."),(0,i.kt)("pre",{parentName:"li"},(0,i.kt)("code",{parentName:"pre",className:"language-java"},"import com.linecorp.armeria.server.ServerBuilder;\n\nServerBuilder sb = Server.builder();\nsb.blockingTaskExecutor(myScheduledExecutorService);\n"))),(0,i.kt)("li",{parentName:"ul"},(0,i.kt)("p",{parentName:"li"},"Specify the default limits of an HTTP request or response."),(0,i.kt)("pre",{parentName:"li"},(0,i.kt)("code",{parentName:"pre",className:"language-java"},"import java.time.Duration;\nimport com.linecorp.armeria.client.ClientBuilder;\nimport com.linecorp.armeria.server.ServerBuilder;\n\n// Server-side\nServerBuilder sb = Server.builder();\nsb.maxRequestLength(1048576); // bytes (default: 10 MiB)\nsb.requestTimeout(Duration.ofSeconds(7)); // (default: 10 seconds)\n\n// Client-side\nClientBuilder cb = Clients.builder(...); // or WebClient.builder(...)\ncb.maxResponseLength(1048576); // bytes (default: 10 MiB)\ncb.responseTimeout(Duration.ofSeconds(10)); // (default: 15 seconds)\n"))),(0,i.kt)("li",{parentName:"ul"},(0,i.kt)("p",{parentName:"li"},"Decorate your services with ",(0,i.kt)("a",{parentName:"p",href:"type://ThrottlingService:https://javadoc.io/doc/com.linecorp.armeria/armeria-javadoc/latest/com/linecorp/armeria/server/throttling/ThrottlingService.html"},"type://ThrottlingService")," which lets you fail the incoming requests based on a\npolicy, such as 'fail if the rate of requests exceed a certain threshold."),(0,i.kt)("pre",{parentName:"li"},(0,i.kt)("code",{parentName:"pre",className:"language-java"},'import com.linecorp.armeria.server.throttling.ThrottlingService;\nimport com.linecorp.armeria.server.throttling.ThrottlingStrategy;\n\nServerBuilder sb = Server.builder();\nsb.service("/my_service", // Allow up to 1000 requests/sec.\n           myService.decorate(ThrottlingService.newDecorator(\n                   ThrottlingStrategy.rateLimiting(1000.0))));\n'))),(0,i.kt)("li",{parentName:"ul"},(0,i.kt)("p",{parentName:"li"},"Decorate your clients with ",(0,i.kt)("a",{parentName:"p",href:"type://RetryingClient:https://javadoc.io/doc/com.linecorp.armeria/armeria-javadoc/latest/com/linecorp/armeria/client/retry/RetryingClient.html"},"type://RetryingClient"),". See ",(0,i.kt)("a",{parentName:"p",href:"/docs/client-retry#automatic-retry"},"Automatic retry"),".")),(0,i.kt)("li",{parentName:"ul"},(0,i.kt)("p",{parentName:"li"},"Decorate your clients with ",(0,i.kt)("a",{parentName:"p",href:"type://CircuitBreakerClient:https://javadoc.io/doc/com.linecorp.armeria/armeria-javadoc/latest/com/linecorp/armeria/client/circuitbreaker/CircuitBreakerClient.html"},"type://CircuitBreakerClient"),". See ",(0,i.kt)("a",{parentName:"p",href:"/docs/client-circuit-breaker"},"Circuit breaker"),"."))),(0,i.kt)(m,{mdxType:"Tip"},(0,i.kt)("p",null,"You can use Armeria's ",(0,i.kt)("a",{parentName:"p",href:"type://CircuitBreaker:https://javadoc.io/doc/com.linecorp.armeria/armeria-javadoc/latest/com/linecorp/armeria/client/circuitbreaker/CircuitBreaker.html"},"type://CircuitBreaker")," API for non-Armeria clients without circuit breaker support.\nSee ",(0,i.kt)("a",{parentName:"p",href:"/docs/client-circuit-breaker#using-circuitbreaker-with-non-armeria-client"},"Using CircuitBreaker with non-Armeria client"),".")),(0,i.kt)("ul",null,(0,i.kt)("li",{parentName:"ul"},(0,i.kt)("p",{parentName:"li"},"Tune the socket options."),(0,i.kt)("pre",{parentName:"li"},(0,i.kt)("code",{parentName:"pre",className:"language-java"},"import com.linecorp.armeria.client.ClientBuilder;\nimport com.linecorp.armeria.client.ClientFactory;\nimport com.linecorp.armeria.client.ClientFactoryBuilder;\nimport com.linecorp.armeria.server.ServerBuilder;\nimport io.netty.channel.ChannelOption;\n\n// Server-side\nServerBuilder sb = Server.builder();\nsb.channelOption(ChannelOption.SO_BACKLOG, ...);\nsb.channelOption(ChannelOption.SO_REUSEADDR, ...);\nsb.childChannelOption(ChannelOption.SO_SNDBUF, ...);\nsb.childChannelOption(ChannelOption.SO_RCVBUF, ...);\n\n// Client-side\nClientFactoryBuilder cfb = ClientFactory.builder();\ncfb.channelOption(ChannelOption.SO_REUSEADDR, ...);\ncfb.channelOption(ChannelOption.SO_SNDBUF, ...);\ncfb.channelOption(ChannelOption.SO_RCVBUF, ...);\nClientFactory cf = cfb.build();\nClientBuilder cb = Clients.builder(...);\ncb.factory(cf);\n"))),(0,i.kt)("li",{parentName:"ul"},(0,i.kt)("p",{parentName:"li"},"Consider increasing ",(0,i.kt)("a",{parentName:"p",href:"type://ClientFactoryBuilder#maxNumEventLoopsPerEndpoint(int):https://javadoc.io/doc/com.linecorp.armeria/armeria-javadoc/latest/com/linecorp/armeria/client/ClientFactoryBuilder.html#maxNumEventLoopsPerEndpoint(int)"},"type://ClientFactoryBuilder#maxNumEventLoopsPerEndpoint(int)"),"\nand ",(0,i.kt)("a",{parentName:"p",href:"type://ClientFactoryBuilder#maxNumEventLoopsPerHttp1Endpoint(int):https://javadoc.io/doc/com.linecorp.armeria/armeria-javadoc/latest/com/linecorp/armeria/client/ClientFactoryBuilder.html#maxNumEventLoopsPerHttp1Endpoint(int)"},"type://ClientFactoryBuilder#maxNumEventLoopsPerHttp1Endpoint(int)")," when a\nclient needs to send a large number of requests to a specific endpoint. The client\nwill assign more CPU resources and create more connections by increasing the number\nof event loops up to the quantity in ",(0,i.kt)("a",{parentName:"p",href:"type://ClientFactory#eventLoopGroup():https://javadoc.io/doc/com.linecorp.armeria/armeria-javadoc/latest/com/linecorp/armeria/client/ClientFactory.html#eventLoopGroup()"},"type://ClientFactory#eventLoopGroup()"),"."),(0,i.kt)("pre",{parentName:"li"},(0,i.kt)("code",{parentName:"pre",className:"language-java"},"import com.linecorp.armeria.client.Clients;\nimport com.linecorp.armeria.client.ClientBuilder;\nimport com.linecorp.armeria.client.ClientFactory;\nimport com.linecorp.armeria.client.ClientFactoryBuilder;\n\nClientFactoryBuilder cfb = ClientFactory.builder();\ncfb.maxNumEventLoopsPerEndpoint(16); // default: 1\ncfb.maxNumEventLoopsPerHttp1Endpoint(16); // default: 1\nClientFactory cf = cfb.build();\nClientBuilder cb = Clients.builder(...);\ncb.factory(cf);\n")))))}v.isMDXComponent=!0},28819:function(e,t,r){r.d(t,{Z:function(){return c}});var a=r(25444),i=r(67294),n=JSON.parse('{"root":["index","setup"],"Useful links":{"Tutorials":"/tutorials","Community articles":"/community/articles","API documentation":"https://javadoc.io/doc/com.linecorp.armeria/armeria-javadoc/latest/index.html","Release notes":"/release-notes"},"Server":["server-basics","server-decorator","server-grpc","server-thrift","server-graphql","server-docservice","server-annotated-service","server-http-file","server-servlet","server-access-log","server-cors","server-sse","server-service-registration","server-multipart","server-timeouts"],"Client":["client-http","client-thrift","client-grpc","client-factory","client-decorator","client-retrofit","client-custom-http-headers","client-timeouts","client-retry","client-circuit-breaker","client-service-discovery"],"Advanced":["advanced-logging","advanced-structured-logging","advanced-custom-attributes","advanced-streaming-backpressure","advanced-structured-logging-kafka","advanced-metrics","advanced-unit-testing","advanced-production-checklist","advanced-saml","advanced-spring-boot-integration","advanced-spring-webflux-integration","advanced-dropwizard-integration","advanced-kotlin","advanced-scala","advanced-scalapb","advanced-flags-provider","advanced-zipkin","advanced-client-interoperability"]}'),o=r(46731),c=function(e){var t=(0,a.useStaticQuery)("1217743243").allMdx.nodes;return i.createElement(o.Z,Object.assign({},e,{candidateMdxNodes:t,index:n,prefix:"docs",pageTitleSuffix:"Armeria documentation"}))}}}]);
//# sourceMappingURL=component---src-pages-docs-advanced-production-checklist-mdx-a85b5c6e57dc9af71bbc.js.map