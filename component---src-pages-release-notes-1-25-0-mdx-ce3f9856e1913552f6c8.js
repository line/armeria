"use strict";(self.webpackChunkarmeria_site=self.webpackChunkarmeria_site||[]).push([[5238],{30501:function(e,a,t){t.r(a),t.d(a,{_frontmatter:function(){return l},default:function(){return h}});var r,i=t(63366),n=(t(67294),t(64983)),o=t(20370),s=["components"],l={},m=(r="ThankYou",function(e){return console.warn("Component "+r+" was not imported, exported, or provided by MDXProvider as global scope"),(0,n.kt)("div",e)}),p={_frontmatter:l},c=o.Z;function h(e){var a=e.components,t=(0,i.Z)(e,s);return(0,n.kt)(c,Object.assign({},p,t,{components:a,mdxType:"MDXLayout"}),(0,n.kt)("p",{className:"date"},"22nd August 2023"),(0,n.kt)("h2",{id:"-new-features",style:{position:"relative"}},(0,n.kt)("a",{parentName:"h2",href:"#-new-features","aria-label":" new features permalink",className:"anchor before"},(0,n.kt)("svg",{parentName:"a","aria-hidden":"true",focusable:"false",height:"16",version:"1.1",viewBox:"0 0 16 16",width:"16"},(0,n.kt)("path",{parentName:"svg",fillRule:"evenodd",d:"M4 9h1v1H4c-1.5 0-3-1.69-3-3.5S2.55 3 4 3h4c1.45 0 3 1.69 3 3.5 0 1.41-.91 2.72-2 3.25V8.59c.58-.45 1-1.27 1-2.09C10 5.22 8.98 4 8 4H4c-.98 0-2 1.22-2 2.5S3 9 4 9zm9-3h-1v1h1c1 0 2 1.22 2 2.5S13.98 12 13 12H9c-.98 0-2-1.22-2-2.5 0-.83.42-1.64 1-2.09V6.25c-1.09.53-2 1.84-2 3.25C6 11.31 7.55 13 9 13h4c1.45 0 3-1.69 3-3.5S14.5 6 13 6z"}))),"🌟 New features"),(0,n.kt)("ul",null,(0,n.kt)("li",{parentName:"ul"},(0,n.kt)("p",{parentName:"li"},(0,n.kt)("strong",{parentName:"p"},"GraalVM Support"),": Armeria now provides ",(0,n.kt)("a",{parentName:"p",href:"https://www.graalvm.org/"},"GraalVM"),"\n",(0,n.kt)("a",{parentName:"p",href:"https://www.graalvm.org/latest/reference-manual/native-image/metadata/"},"reachability metadata")," to easily build\n",(0,n.kt)("a",{parentName:"p",href:"https://www.graalvm.org/"},"GraalVM")," native images. ",(0,n.kt)("a",{parentName:"p",href:"https://github.com/line/armeria/issues/5005"},"#5005"))),(0,n.kt)("li",{parentName:"ul"},(0,n.kt)("p",{parentName:"li"},(0,n.kt)("strong",{parentName:"p"},"Micrometer Observation Support"),": Support for ",(0,n.kt)("a",{parentName:"p",href:"https://micrometer.io/docs/observation"},"Micrometer Observation")," is added.\nRefer to ",(0,n.kt)("a",{parentName:"p",href:"type://ObservationClient:https://javadoc.io/doc/com.linecorp.armeria/armeria-javadoc/latest/com/linecorp/armeria/client/observation/ObservationClient.html"},"type://ObservationClient")," or ",(0,n.kt)("a",{parentName:"p",href:"type://ObservationService:https://javadoc.io/doc/com.linecorp.armeria/armeria-javadoc/latest/com/linecorp/armeria/server/observation/ObservationService.html"},"type://ObservationService")," for details on how to integrate with Armeria.  ",(0,n.kt)("a",{parentName:"p",href:"https://github.com/line/armeria/issues/4659"},"#4659")," ",(0,n.kt)("a",{parentName:"p",href:"https://github.com/line/armeria/issues/4980"},"#4980")),(0,n.kt)("pre",{parentName:"li"},(0,n.kt)("code",{parentName:"pre",className:"language-java"},"ObservationRegistry observationRegistry = ...\nWebClient.builder()\n  .decorator(ObservationClient.newDecorator(observationRegistry))\n...\nServer.builder()\n  .decorator(ObservationService.newDecorator(observationRegistry))\n...\n"))),(0,n.kt)("li",{parentName:"ul"},(0,n.kt)("p",{parentName:"li"},(0,n.kt)("strong",{parentName:"p"},"WebSocket Client Support"),": You can now send and receive data over ",(0,n.kt)("a",{parentName:"p",href:"https://en.wikipedia.org/wiki/WebSocket"},"WebSocket"),"\nusing ",(0,n.kt)("a",{parentName:"p",href:"type://WebSocketClient:https://javadoc.io/doc/com.linecorp.armeria/armeria-javadoc/latest/com/linecorp/armeria/client/websocket/WebSocketClient.html"},"type://WebSocketClient"),". ",(0,n.kt)("a",{parentName:"p",href:"https://github.com/line/armeria/issues/4972"},"#4972")),(0,n.kt)("pre",{parentName:"li"},(0,n.kt)("code",{parentName:"pre",className:"language-java"},'WebSocketClient client = WebSocketClient.of("ws://...");\nclient.connect("/").thenAccept(webSocketSession -> {\n  WebSocketWriter writer = WebSocket.streaming();\n  webSocketSessions.setOutbound(writer);\n  outbound.write("Hello!");\n\n  Subscriber<WebSocketFrame> subscriber = new Subscriber<WebSocketFrame>() {\n    ...\n  }\n  webSocketSessions.inbound().subscribe(subscriber);\n});\n'))),(0,n.kt)("li",{parentName:"ul"},(0,n.kt)("p",{parentName:"li"},(0,n.kt)("strong",{parentName:"p"},"Implement gRPC Richer Error Model More Easily"),": You can now easily use gRPC\n",(0,n.kt)("a",{parentName:"p",href:"https://grpc.io/docs/guides/error/#richer-error-model"},"Richer Error Model")," via ",(0,n.kt)("a",{parentName:"p",href:"type://GoogleGrpcStatusFunction:https://javadoc.io/doc/com.linecorp.armeria/armeria-javadoc/latest/com/linecorp/armeria/common/grpc/GoogleGrpcStatusFunction.html"},"type://GoogleGrpcStatusFunction"),". ",(0,n.kt)("a",{parentName:"p",href:"https://github.com/line/armeria/issues/4614"},"#4614")," ",(0,n.kt)("a",{parentName:"p",href:"https://github.com/line/armeria/issues/4986"},"#4986")),(0,n.kt)("pre",{parentName:"li"},(0,n.kt)("code",{parentName:"pre",className:"language-java"},"GoogleGrpcStatusFunction statusFunction = (ctx, throwable, metadata) -> {\n  if (throwable instanceof MyException) {\n    return com.google.rpc.Status.newBuilder()\n      .setCode(Code.UNAUTHENTICATED.getNumber())\n      .addDetails(detail(throwable))\n      .build();\n  }\n  ...\n};\nServer.builder().service(\n  GrpcService.builder()\n    .exceptionMapping(statusFunction))\n"))),(0,n.kt)("li",{parentName:"ul"},(0,n.kt)("p",{parentName:"li"},(0,n.kt)("strong",{parentName:"p"},"Set HTTP Trailers Easily"),": You can now easily set trailers to be sent after the data stream using\n",(0,n.kt)("a",{parentName:"p",href:"type://HttpRequest#of(RequestHeaders,Publisher,HttpHeaders):https://javadoc.io/doc/com.linecorp.armeria/armeria-javadoc/latest/com/linecorp/armeria/common/HttpRequest.html#of(com.linecorp.armeria.common.RequestHeaders,org.reactivestreams.Publisher,com.linecorp.armeria.common.HttpHeaders)"},"type://HttpRequest#of(RequestHeaders,Publisher,HttpHeaders)")," or\n",(0,n.kt)("a",{parentName:"p",href:"type://HttpResponse#of(ResponseHeaders,Publisher,HttpHeaders):https://javadoc.io/doc/com.linecorp.armeria/armeria-javadoc/latest/com/linecorp/armeria/common/HttpResponse.html#of(com.linecorp.armeria.common.ResponseHeaders,org.reactivestreams.Publisher,com.linecorp.armeria.common.HttpHeaders)"},"type://HttpResponse#of(ResponseHeaders,Publisher,HttpHeaders)"),". ",(0,n.kt)("a",{parentName:"p",href:"https://github.com/line/armeria/issues/3959"},"#3959")," ",(0,n.kt)("a",{parentName:"p",href:"https://github.com/line/armeria/issues/4727"},"#4727"))),(0,n.kt)("li",{parentName:"ul"},(0,n.kt)("p",{parentName:"li"},(0,n.kt)("strong",{parentName:"p"},"New API for Multipart Headers"),": You can now retrieve headers from a multipart request in an annotated service\nusing ",(0,n.kt)("a",{parentName:"p",href:"type://MultipartFile#headers():https://javadoc.io/doc/com.linecorp.armeria/armeria-javadoc/latest/com/linecorp/armeria/common/multipart/MultipartFile.html#headers()"},"type://MultipartFile#headers()"),". ",(0,n.kt)("a",{parentName:"p",href:"https://github.com/line/armeria/issues/5106"},"#5106"))),(0,n.kt)("li",{parentName:"ul"},(0,n.kt)("p",{parentName:"li"},(0,n.kt)("strong",{parentName:"p"},"Access RequestLogProperty Values More Easily"),": ",(0,n.kt)("a",{parentName:"p",href:"type://RequestLogAccess#getIfAvailable()"},"type://RequestLogAccess#getIfAvailable()"),"\nhas been introduced, which allows users to access a ",(0,n.kt)("a",{parentName:"p",href:"type://RequestLogProperty:https://javadoc.io/doc/com.linecorp.armeria/armeria-javadoc/latest/com/linecorp/armeria/common/logging/RequestLogProperty.html"},"type://RequestLogProperty")," immediately if available. ",(0,n.kt)("a",{parentName:"p",href:"https://github.com/line/armeria/issues/4956"},"#4956")," ",(0,n.kt)("a",{parentName:"p",href:"https://github.com/line/armeria/issues/4966"},"#4966"))),(0,n.kt)("li",{parentName:"ul"},(0,n.kt)("p",{parentName:"li"},(0,n.kt)("strong",{parentName:"p"},"Keep an Idle Connection Alive on PING"),": The ",(0,n.kt)("inlineCode",{parentName:"p"},"keepAliveOnPing")," option has been introduced. Enabling this option will keep\nan idle connection alive when an HTTP/2 PING frame or ",(0,n.kt)("inlineCode",{parentName:"p"},"OPTIONS * HTTP/1.1")," is received. The option can be configured\nby ",(0,n.kt)("a",{parentName:"p",href:"type://ServerBuilder#idleTimeout(Duration,boolean):https://javadoc.io/doc/com.linecorp.armeria/armeria-javadoc/latest/com/linecorp/armeria/server/ServerBuilder.html#idleTimeout(java.time.Duration,boolean)"},"type://ServerBuilder#idleTimeout(Duration,boolean)")," or ",(0,n.kt)("a",{parentName:"p",href:"type://ClientFactoryBuilder#idleTimeout(Duration,boolean):https://javadoc.io/doc/com.linecorp.armeria/armeria-javadoc/latest/com/linecorp/armeria/client/ClientFactoryBuilder.html#idleTimeout(java.time.Duration,boolean)"},"type://ClientFactoryBuilder#idleTimeout(Duration,boolean)"),". ",(0,n.kt)("a",{parentName:"p",href:"https://github.com/line/armeria/issues/4794"},"#4794")," ",(0,n.kt)("a",{parentName:"p",href:"https://github.com/line/armeria/issues/4806"},"#4806"))),(0,n.kt)("li",{parentName:"ul"},(0,n.kt)("p",{parentName:"li"},(0,n.kt)("strong",{parentName:"p"},"Create a StreamMessage from Future"),": You can now easily create a ",(0,n.kt)("a",{parentName:"p",href:"type://StreamMessage:https://javadoc.io/doc/com.linecorp.armeria/armeria-javadoc/latest/com/linecorp/armeria/common/stream/StreamMessage.html"},"type://StreamMessage")," from a ",(0,n.kt)("inlineCode",{parentName:"p"},"CompletionStage"),"\nusing ",(0,n.kt)("a",{parentName:"p",href:"type://StreamMessage#of(CompletionStage%3C?"},"type://StreamMessage#of(CompletionStage<?"),")>. ",(0,n.kt)("a",{parentName:"p",href:"https://github.com/line/armeria/issues/4995"},"#4995"))),(0,n.kt)("li",{parentName:"ul"},(0,n.kt)("p",{parentName:"li"},(0,n.kt)("strong",{parentName:"p"},"More Shortcuts for PrometheusExpositionService"),": You can now create a ",(0,n.kt)("a",{parentName:"p",href:"type://PrometheusExpositionService:https://javadoc.io/doc/com.linecorp.armeria/armeria-javadoc/latest/com/linecorp/armeria/server/metric/PrometheusExpositionService.html"},"type://PrometheusExpositionService")," without\nspecifying the default ",(0,n.kt)("inlineCode",{parentName:"p"},"CollectorRegistry")," explicitly. ",(0,n.kt)("a",{parentName:"p",href:"https://github.com/line/armeria/issues/5134"},"#5134")))),(0,n.kt)("h2",{id:"-improvements",style:{position:"relative"}},(0,n.kt)("a",{parentName:"h2",href:"#-improvements","aria-label":" improvements permalink",className:"anchor before"},(0,n.kt)("svg",{parentName:"a","aria-hidden":"true",focusable:"false",height:"16",version:"1.1",viewBox:"0 0 16 16",width:"16"},(0,n.kt)("path",{parentName:"svg",fillRule:"evenodd",d:"M4 9h1v1H4c-1.5 0-3-1.69-3-3.5S2.55 3 4 3h4c1.45 0 3 1.69 3 3.5 0 1.41-.91 2.72-2 3.25V8.59c.58-.45 1-1.27 1-2.09C10 5.22 8.98 4 8 4H4c-.98 0-2 1.22-2 2.5S3 9 4 9zm9-3h-1v1h1c1 0 2 1.22 2 2.5S13.98 12 13 12H9c-.98 0-2-1.22-2-2.5 0-.83.42-1.64 1-2.09V6.25c-1.09.53-2 1.84-2 3.25C6 11.31 7.55 13 9 13h4c1.45 0 3-1.69 3-3.5S14.5 6 13 6z"}))),"📈 Improvements"),(0,n.kt)("ul",null,(0,n.kt)("li",{parentName:"ul"},"The number of event loops is equal to the number of cores by default when ",(0,n.kt)("inlineCode",{parentName:"li"},"io_uring")," is used as the transport type. ",(0,n.kt)("a",{parentName:"li",href:"https://github.com/line/armeria/issues/5089"},"#5089")),(0,n.kt)("li",{parentName:"ul"},"You can now customize error responses when a service for a request is not found\nusing ",(0,n.kt)("a",{parentName:"li",href:"type://ServiceErrorHandler#renderStatus()"},"type://ServiceErrorHandler#renderStatus()"),". ",(0,n.kt)("a",{parentName:"li",href:"https://github.com/line/armeria/issues/4996"},"#4996")),(0,n.kt)("li",{parentName:"ul"},"Redirection for a trailing slash is done correctly even if a reverse proxy rewrites the path. ",(0,n.kt)("a",{parentName:"li",href:"https://github.com/line/armeria/issues/4994"},"#4994")),(0,n.kt)("li",{parentName:"ul"},(0,n.kt)("a",{parentName:"li",href:"type://DocService:https://javadoc.io/doc/com.linecorp.armeria/armeria-javadoc/latest/com/linecorp/armeria/server/docs/DocService.html"},"type://DocService")," now tries to guess the correct route behind a reverse proxy. ",(0,n.kt)("a",{parentName:"li",href:"https://github.com/line/armeria/issues/4987"},"#4987")),(0,n.kt)("li",{parentName:"ul"},"The ",(0,n.kt)("inlineCode",{parentName:"li"},"RetentionPolicy")," of ",(0,n.kt)("a",{parentName:"li",href:"type://@UnstableApi:https://javadoc.io/doc/com.linecorp.armeria/armeria-javadoc/latest/com/linecorp/armeria/common/annotation/UnstableApi.html"},"type://@UnstableApi")," annotation is now ",(0,n.kt)("inlineCode",{parentName:"li"},"CLASS")," so that\nbytecode analysis tools can detect the declaration and usage of unstable APIs. ",(0,n.kt)("a",{parentName:"li",href:"https://github.com/line/armeria/issues/5131"},"#5131"))),(0,n.kt)("h2",{id:"️-bug-fixes",style:{position:"relative"}},(0,n.kt)("a",{parentName:"h2",href:"#%EF%B8%8F-bug-fixes","aria-label":"️ bug fixes permalink",className:"anchor before"},(0,n.kt)("svg",{parentName:"a","aria-hidden":"true",focusable:"false",height:"16",version:"1.1",viewBox:"0 0 16 16",width:"16"},(0,n.kt)("path",{parentName:"svg",fillRule:"evenodd",d:"M4 9h1v1H4c-1.5 0-3-1.69-3-3.5S2.55 3 4 3h4c1.45 0 3 1.69 3 3.5 0 1.41-.91 2.72-2 3.25V8.59c.58-.45 1-1.27 1-2.09C10 5.22 8.98 4 8 4H4c-.98 0-2 1.22-2 2.5S3 9 4 9zm9-3h-1v1h1c1 0 2 1.22 2 2.5S13.98 12 13 12H9c-.98 0-2-1.22-2-2.5 0-.83.42-1.64 1-2.09V6.25c-1.09.53-2 1.84-2 3.25C6 11.31 7.55 13 9 13h4c1.45 0 3-1.69 3-3.5S14.5 6 13 6z"}))),"🛠️ Bug fixes"),(0,n.kt)("ul",null,(0,n.kt)("li",{parentName:"ul"},(0,n.kt)("a",{parentName:"li",href:"type://GrpcService:https://javadoc.io/doc/com.linecorp.armeria/armeria-javadoc/latest/com/linecorp/armeria/server/grpc/GrpcService.html"},"type://GrpcService")," now returns an ",(0,n.kt)("inlineCode",{parentName:"li"},"INTERNAL")," error code if an error occurs while serializing gRPC metadata. ",(0,n.kt)("a",{parentName:"li",href:"https://github.com/line/armeria/issues/4625"},"#4625")," ",(0,n.kt)("a",{parentName:"li",href:"https://github.com/line/armeria/issues/4686"},"#4686")),(0,n.kt)("li",{parentName:"ul"},(0,n.kt)("a",{parentName:"li",href:"type://DnsCache:https://javadoc.io/doc/com.linecorp.armeria/armeria-javadoc/latest/com/linecorp/armeria/client/DnsCache.html"},"type://DnsCache")," now allows zero TTL for resolved DNS records. ",(0,n.kt)("a",{parentName:"li",href:"https://github.com/line/armeria/issues/5119"},"#5119")),(0,n.kt)("li",{parentName:"ul"},"Armeria's DNS resolver doesn't cache a DNS whose query was timed out. ",(0,n.kt)("a",{parentName:"li",href:"https://github.com/line/armeria/issues/5117"},"#5117")),(0,n.kt)("li",{parentName:"ul"},"Fixed a bug where headers could be written twice if ",(0,n.kt)("inlineCode",{parentName:"li"},"Content-Length")," was exceeded during HTTP/2 cleartext upgrade. ",(0,n.kt)("a",{parentName:"li",href:"https://github.com/line/armeria/issues/5113"},"#5113")),(0,n.kt)("li",{parentName:"ul"},(0,n.kt)("a",{parentName:"li",href:"type://ServiceRequestContext#localAddress():https://javadoc.io/doc/com.linecorp.armeria/armeria-javadoc/latest/com/linecorp/armeria/server/ServiceRequestContext.html#localAddress()"},"type://ServiceRequestContext#localAddress()")," and ",(0,n.kt)("a",{parentName:"li",href:"type://ServiceRequestContext#remoteAddress():https://javadoc.io/doc/com.linecorp.armeria/armeria-javadoc/latest/com/linecorp/armeria/server/ServiceRequestContext.html#remoteAddress()"},"type://ServiceRequestContext#remoteAddress()")," now return\ncorrect values when using domain sockets in abstract namespace. ",(0,n.kt)("a",{parentName:"li",href:"https://github.com/line/armeria/issues/5096"},"#5096")),(0,n.kt)("li",{parentName:"ul"},(0,n.kt)("inlineCode",{parentName:"li"},"armeria-logback12"),", ",(0,n.kt)("inlineCode",{parentName:"li"},"armeria-logback13"),", and ",(0,n.kt)("inlineCode",{parentName:"li"},"armeria-logback14")," have been introduced for better\ncompatibility with ",(0,n.kt)("a",{parentName:"li",href:"https://logback.qos.ch/"},"Logback"),". ",(0,n.kt)("a",{parentName:"li",href:"https://github.com/line/armeria/issues/5045"},"#5045")," ",(0,n.kt)("a",{parentName:"li",href:"https://github.com/line/armeria/issues/5079"},"#5079")," ",(0,n.kt)("a",{parentName:"li",href:"https://github.com/line/armeria/issues/5078"},"#5078")," ",(0,n.kt)("a",{parentName:"li",href:"https://github.com/line/armeria/issues/5077"},"#5077")),(0,n.kt)("li",{parentName:"ul"},"You can now use either an inline debug form or a modal debug form when using ",(0,n.kt)("a",{parentName:"li",href:"type://DocService:https://javadoc.io/doc/com.linecorp.armeria/armeria-javadoc/latest/com/linecorp/armeria/server/docs/DocService.html"},"type://DocService"),". ",(0,n.kt)("a",{parentName:"li",href:"https://github.com/line/armeria/issues/5072"},"#5072")),(0,n.kt)("li",{parentName:"ul"},"When using Spring integrations, even if ",(0,n.kt)("inlineCode",{parentName:"li"},"internal-services.port")," and ",(0,n.kt)("inlineCode",{parentName:"li"},"management.server.port"),"\nare set to the same value internal services are bound to the port only once. ",(0,n.kt)("a",{parentName:"li",href:"https://github.com/line/armeria/issues/4796"},"#4796")," ",(0,n.kt)("a",{parentName:"li",href:"https://github.com/line/armeria/issues/5022"},"#5022")),(0,n.kt)("li",{parentName:"ul"},"Exceptions that occurred during a TLS handshake are properly propagated to users. ",(0,n.kt)("a",{parentName:"li",href:"https://github.com/line/armeria/issues/4950"},"#4950")),(0,n.kt)("li",{parentName:"ul"},(0,n.kt)("a",{parentName:"li",href:"type://AggregatedHttpObject#content(Charset):https://javadoc.io/doc/com.linecorp.armeria/armeria-javadoc/latest/com/linecorp/armeria/common/AggregatedHttpObject.html#content(java.nio.charset.Charset)"},"type://AggregatedHttpObject#content(Charset)")," now respects the ",(0,n.kt)("inlineCode",{parentName:"li"},"charset")," attribute in the\n",(0,n.kt)("inlineCode",{parentName:"li"},"Content-Type")," header if available. ",(0,n.kt)("a",{parentName:"li",href:"https://github.com/line/armeria/issues/4931"},"#4931"),"  ",(0,n.kt)("a",{parentName:"li",href:"https://github.com/line/armeria/issues/4948"},"#4948")),(0,n.kt)("li",{parentName:"ul"},"Routes with dynamic predicates are not incorrectly cached anymore. ",(0,n.kt)("a",{parentName:"li",href:"https://github.com/line/armeria/issues/4927"},"#4927")," ",(0,n.kt)("a",{parentName:"li",href:"https://github.com/line/armeria/issues/4934"},"#4934"))),(0,n.kt)("h2",{id:"-documentation",style:{position:"relative"}},(0,n.kt)("a",{parentName:"h2",href:"#-documentation","aria-label":" documentation permalink",className:"anchor before"},(0,n.kt)("svg",{parentName:"a","aria-hidden":"true",focusable:"false",height:"16",version:"1.1",viewBox:"0 0 16 16",width:"16"},(0,n.kt)("path",{parentName:"svg",fillRule:"evenodd",d:"M4 9h1v1H4c-1.5 0-3-1.69-3-3.5S2.55 3 4 3h4c1.45 0 3 1.69 3 3.5 0 1.41-.91 2.72-2 3.25V8.59c.58-.45 1-1.27 1-2.09C10 5.22 8.98 4 8 4H4c-.98 0-2 1.22-2 2.5S3 9 4 9zm9-3h-1v1h1c1 0 2 1.22 2 2.5S13.98 12 13 12H9c-.98 0-2-1.22-2-2.5 0-.83.42-1.64 1-2.09V6.25c-1.09.53-2 1.84-2 3.25C6 11.31 7.55 13 9 13h4c1.45 0 3-1.69 3-3.5S14.5 6 13 6z"}))),"📃 Documentation"),(0,n.kt)("ul",null,(0,n.kt)("li",{parentName:"ul"},"A new page has been added which describes how to integrate Armeria with Spring Boot. ",(0,n.kt)("a",{parentName:"li",href:"https://github.com/line/armeria/issues/4670"},"#4670")," ",(0,n.kt)("a",{parentName:"li",href:"https://github.com/line/armeria/issues/4957"},"#4957")),(0,n.kt)("li",{parentName:"ul"},"Documentation on how ",(0,n.kt)("a",{parentName:"li",href:"type://Flags:https://javadoc.io/doc/com.linecorp.armeria/armeria-javadoc/latest/com/linecorp/armeria/common/Flags.html"},"type://Flags")," work in Armeria has been added. ",(0,n.kt)("a",{parentName:"li",href:"https://github.com/line/armeria/issues/4870"},"#4870")),(0,n.kt)("li",{parentName:"ul"},"A new example on how to use ",(0,n.kt)("a",{parentName:"li",href:"https://github.com/mscheong01/krotoDC"},"krotoDC")," with Armeria has been added. ",(0,n.kt)("a",{parentName:"li",href:"https://github.com/line/armeria/issues/5092"},"#5092"))),(0,n.kt)("h2",{id:"️-breaking-changes",style:{position:"relative"}},(0,n.kt)("a",{parentName:"h2",href:"#%EF%B8%8F-breaking-changes","aria-label":"️ breaking changes permalink",className:"anchor before"},(0,n.kt)("svg",{parentName:"a","aria-hidden":"true",focusable:"false",height:"16",version:"1.1",viewBox:"0 0 16 16",width:"16"},(0,n.kt)("path",{parentName:"svg",fillRule:"evenodd",d:"M4 9h1v1H4c-1.5 0-3-1.69-3-3.5S2.55 3 4 3h4c1.45 0 3 1.69 3 3.5 0 1.41-.91 2.72-2 3.25V8.59c.58-.45 1-1.27 1-2.09C10 5.22 8.98 4 8 4H4c-.98 0-2 1.22-2 2.5S3 9 4 9zm9-3h-1v1h1c1 0 2 1.22 2 2.5S13.98 12 13 12H9c-.98 0-2-1.22-2-2.5 0-.83.42-1.64 1-2.09V6.25c-1.09.53-2 1.84-2 3.25C6 11.31 7.55 13 9 13h4c1.45 0 3-1.69 3-3.5S14.5 6 13 6z"}))),"☢️ Breaking changes"),(0,n.kt)("ul",null,(0,n.kt)("li",{parentName:"ul"},"The ",(0,n.kt)("inlineCode",{parentName:"li"},"toStringHelper()")," method in ",(0,n.kt)("a",{parentName:"li",href:"type://DynamicEndpointGroup:https://javadoc.io/doc/com.linecorp.armeria/armeria-javadoc/latest/com/linecorp/armeria/client/endpoint/DynamicEndpointGroup.html"},"type://DynamicEndpointGroup")," has been replaced\nwith ",(0,n.kt)("inlineCode",{parentName:"li"},"toString(Consumer)")," to avoid exposing an internal API in the public API. ",(0,n.kt)("a",{parentName:"li",href:"https://github.com/line/armeria/issues/5132"},"#5132"))),(0,n.kt)("h2",{id:"️-deprecations",style:{position:"relative"}},(0,n.kt)("a",{parentName:"h2",href:"#%EF%B8%8F-deprecations","aria-label":"️ deprecations permalink",className:"anchor before"},(0,n.kt)("svg",{parentName:"a","aria-hidden":"true",focusable:"false",height:"16",version:"1.1",viewBox:"0 0 16 16",width:"16"},(0,n.kt)("path",{parentName:"svg",fillRule:"evenodd",d:"M4 9h1v1H4c-1.5 0-3-1.69-3-3.5S2.55 3 4 3h4c1.45 0 3 1.69 3 3.5 0 1.41-.91 2.72-2 3.25V8.59c.58-.45 1-1.27 1-2.09C10 5.22 8.98 4 8 4H4c-.98 0-2 1.22-2 2.5S3 9 4 9zm9-3h-1v1h1c1 0 2 1.22 2 2.5S13.98 12 13 12H9c-.98 0-2-1.22-2-2.5 0-.83.42-1.64 1-2.09V6.25c-1.09.53-2 1.84-2 3.25C6 11.31 7.55 13 9 13h4c1.45 0 3-1.69 3-3.5S14.5 6 13 6z"}))),"🏚️ Deprecations"),(0,n.kt)("ul",null,(0,n.kt)("li",{parentName:"ul"},(0,n.kt)("a",{parentName:"li",href:"type://HttpResponse#from(CompletionStage):https://javadoc.io/doc/com.linecorp.armeria/armeria-javadoc/latest/com/linecorp/armeria/common/HttpResponse.html#from(java.util.concurrent.CompletionStage)"},"type://HttpResponse#from(CompletionStage)")," and its variants methods are deprecated. ",(0,n.kt)("a",{parentName:"li",href:"https://github.com/line/armeria/issues/5075"},"#5075"),(0,n.kt)("ul",{parentName:"li"},(0,n.kt)("li",{parentName:"ul"},"Use ",(0,n.kt)("a",{parentName:"li",href:"type://HttpResponse#of(CompletionStage):https://javadoc.io/doc/com.linecorp.armeria/armeria-javadoc/latest/com/linecorp/armeria/common/HttpResponse.html#of(java.util.concurrent.CompletionStage)"},"type://HttpResponse#of(CompletionStage)")," and its variants instead.")))),(0,n.kt)("h2",{id:"-dependencies",style:{position:"relative"}},(0,n.kt)("a",{parentName:"h2",href:"#-dependencies","aria-label":" dependencies permalink",className:"anchor before"},(0,n.kt)("svg",{parentName:"a","aria-hidden":"true",focusable:"false",height:"16",version:"1.1",viewBox:"0 0 16 16",width:"16"},(0,n.kt)("path",{parentName:"svg",fillRule:"evenodd",d:"M4 9h1v1H4c-1.5 0-3-1.69-3-3.5S2.55 3 4 3h4c1.45 0 3 1.69 3 3.5 0 1.41-.91 2.72-2 3.25V8.59c.58-.45 1-1.27 1-2.09C10 5.22 8.98 4 8 4H4c-.98 0-2 1.22-2 2.5S3 9 4 9zm9-3h-1v1h1c1 0 2 1.22 2 2.5S13.98 12 13 12H9c-.98 0-2-1.22-2-2.5 0-.83.42-1.64 1-2.09V6.25c-1.09.53-2 1.84-2 3.25C6 11.31 7.55 13 9 13h4c1.45 0 3-1.69 3-3.5S14.5 6 13 6z"}))),"⛓ Dependencies"),(0,n.kt)("ul",null,(0,n.kt)("li",{parentName:"ul"},"gRPC-Java 1.56.0 → 1.57.2"),(0,n.kt)("li",{parentName:"ul"},"GraphQL Kotlin 6.5.2 → 6.5.3"),(0,n.kt)("li",{parentName:"ul"},"Guava 32.0.1-jre → 32.1.2-jre"),(0,n.kt)("li",{parentName:"ul"},"Jakarta Websocket 2.1.0 → 2.1.1"),(0,n.kt)("li",{parentName:"ul"},"Kafka client 3.4.0 → 3.4.1"),(0,n.kt)("li",{parentName:"ul"},"Kotlin 1.8.22 → 1.9.0"),(0,n.kt)("li",{parentName:"ul"},"Kotlin Coroutine 1.7.1 → 1.7.3"),(0,n.kt)("li",{parentName:"ul"},"Logback 1.4.7 → 1.4.11"),(0,n.kt)("li",{parentName:"ul"},"Micrometer 1.11.1 → 1.11.3"),(0,n.kt)("li",{parentName:"ul"},"Netty 4.1.94.Final → 4.1.96.Final"),(0,n.kt)("li",{parentName:"ul"},"Protobuf 3.22.3 → 3.24.0"),(0,n.kt)("li",{parentName:"ul"},"Reactor 3.5.7 → 3.5.8"),(0,n.kt)("li",{parentName:"ul"},"Resilience4j 2.0.2 → 2.1.0"),(0,n.kt)("li",{parentName:"ul"},"Resteasy 5.0.5.Final → 5.0.7.Final"),(0,n.kt)("li",{parentName:"ul"},"Sangria 4.0.0 → 4.0.1"),(0,n.kt)("li",{parentName:"ul"},"scala-collection-compat 2.10.0 → 2.11.0"),(0,n.kt)("li",{parentName:"ul"},"Spring 6.0.9 → 6.0.11"),(0,n.kt)("li",{parentName:"ul"},"Spring Boot 2.7.12 → 2.7.14, 3.1.0 → 3.1.1"),(0,n.kt)("li",{parentName:"ul"},"Tomcat 10.1.10 → 10.1.12")),(0,n.kt)("h2",{id:"-thank-you",style:{position:"relative"}},(0,n.kt)("a",{parentName:"h2",href:"#-thank-you","aria-label":" thank you permalink",className:"anchor before"},(0,n.kt)("svg",{parentName:"a","aria-hidden":"true",focusable:"false",height:"16",version:"1.1",viewBox:"0 0 16 16",width:"16"},(0,n.kt)("path",{parentName:"svg",fillRule:"evenodd",d:"M4 9h1v1H4c-1.5 0-3-1.69-3-3.5S2.55 3 4 3h4c1.45 0 3 1.69 3 3.5 0 1.41-.91 2.72-2 3.25V8.59c.58-.45 1-1.27 1-2.09C10 5.22 8.98 4 8 4H4c-.98 0-2 1.22-2 2.5S3 9 4 9zm9-3h-1v1h1c1 0 2 1.22 2 2.5S13.98 12 13 12H9c-.98 0-2-1.22-2-2.5 0-.83.42-1.64 1-2.09V6.25c-1.09.53-2 1.84-2 3.25C6 11.31 7.55 13 9 13h4c1.45 0 3-1.69 3-3.5S14.5 6 13 6z"}))),"🙇 Thank you"),(0,n.kt)(m,{usernames:["Dogacel","KarboniteKream","Kyoungwoong","aki-s","anuraaga","baezzys","ceki","chrisryan10","ghkim3221","heowc","hyperxpro","ikhoon","jrhee17","marcingrzejszczak","minwoox","mscheong01","my4-dev","mynameisjwlee","r3mariano","ribafish","sh-cho","ta7uw","tomatophobia","trustin","wreulicke","yunjoopark"],mdxType:"ThankYou"}))}h.isMDXComponent=!0},20370:function(e,a,t){t(88025);var r=t(1923),i=t(25444),n=t(67294),o=t(55746),s=t(8284),l=t(96759),m=t(46731),p=t(9396),c=r.Z.Title,h=Object.keys(s)[0],u=d(h);function d(e){return e.substring(e.lastIndexOf("/")+1)}a.Z=function(e){var a={},t={},r={root:{"Latest news items":"/news","Latest release notes":"/release-notes","Past news items":"/news/list","Past release notes":"/release-notes/list"},"Recent news items":a,"Recent releases":t};Object.entries(o).forEach((function(e){var t=e[0],r=e[1];a[r]=t})),Object.entries(s).forEach((function(e){var a=e[0],r=e[1];t[r]=a}));var k=(0,p.Z)(e.location),v=e.version||d(k);return v.match(/^[0-9]/)||(v=void 0),n.createElement(m.Z,Object.assign({},e,{candidateMdxNodes:[],index:r,prefix:"release-notes",pageTitle:v?v+" release notes":e.pageTitle,pageTitleSuffix:"Armeria release notes"}),v&&v!==u?n.createElement(l.Ch,null,"You're seeing the release note of an old version. Check out"," ",n.createElement(i.Link,{to:h},"the latest release note"),"."):"",v?n.createElement(c,{id:"release-notes",level:1},n.createElement("a",{href:"#release-notes","aria-label":"release notes permalink",className:"anchor before"},n.createElement("svg",{"aria-hidden":"true",focusable:"false",height:"16",version:"1.1",viewBox:"0 0 16 16",width:"16"},n.createElement("path",{fillRule:"evenodd",d:"M4 9h1v1H4c-1.5 0-3-1.69-3-3.5S2.55 3 4 3h4c1.45 0 3 1.69 3 3.5 0 1.41-.91 2.72-2 3.25V8.59c.58-.45 1-1.27 1-2.09C10 5.22 8.98 4 8 4H4c-.98 0-2 1.22-2 2.5S3 9 4 9zm9-3h-1v1h1c1 0 2 1.22 2 2.5S13.98 12 13 12H9c-.98 0-2-1.22-2-2.5 0-.83.42-1.64 1-2.09V6.25c-1.09.53-2 1.84-2 3.25C6 11.31 7.55 13 9 13h4c1.45 0 3-1.69 3-3.5S14.5 6 13 6z"}))),v," release notes"):"",e.children)}},55746:function(e){e.exports=JSON.parse('{"/news/20231208-newsletter-6":"Armeria Newsletter vol. 6","/news/20230426-newsletter-5":"Armeria Newsletter vol. 5","/news/20220714-newsletter-4":"Armeria Newsletter vol. 4","/news/20211029-newsletter-3":"Armeria Newsletter vol. 3","/news/20210202-newsletter-2":"Armeria Newsletter vol. 2","/news/20200703-newsletter-1":"Armeria Newsletter vol. 1","/news/20200514-newsletter-0":"Armeria Newsletter vol. 0"}')},8284:function(e){e.exports=JSON.parse('{"/release-notes/1.30.2":"v1.30.2","/release-notes/1.30.1":"v1.30.1","/release-notes/1.30.0":"v1.30.0","/release-notes/1.29.4":"v1.29.4","/release-notes/1.29.3":"v1.29.3","/release-notes/1.29.2":"v1.29.2","/release-notes/1.29.1":"v1.29.1","/release-notes/1.29.0":"v1.29.0","/release-notes/1.28.4":"v1.28.4","/release-notes/1.28.3":"v1.28.3","/release-notes/1.28.2":"v1.28.2","/release-notes/1.28.1":"v1.28.1","/release-notes/1.28.0":"v1.28.0","/release-notes/1.27.3":"v1.27.3","/release-notes/1.27.2":"v1.27.2","/release-notes/1.27.1":"v1.27.1"}')}}]);
//# sourceMappingURL=component---src-pages-release-notes-1-25-0-mdx-ce3f9856e1913552f6c8.js.map