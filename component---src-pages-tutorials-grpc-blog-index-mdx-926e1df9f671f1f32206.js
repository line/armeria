"use strict";(self.webpackChunkarmeria_site=self.webpackChunkarmeria_site||[]).push([[2005],{63972:function(e,t,a){a.r(t),a.d(t,{_frontmatter:function(){return s},default:function(){return h},pageTitle:function(){return p}});var r=a(63366),i=(a(67294),a(64983)),n=a(89791),l=a(75609),o=["components"],p="gRPC tutorial introduction",s={},m=function(e){return function(t){return console.warn("Component "+e+" was not imported, exported, or provided by MDXProvider as global scope"),(0,i.kt)("div",t)}},c=m("Tip"),u=m("CodeBlock"),d={pageTitle:p,_frontmatter:s},g=n.Z;function h(e){var t=e.components,a=(0,r.Z)(e,o);return(0,i.kt)(g,Object.assign({},d,a,{components:t,mdxType:"MDXLayout"}),(0,i.kt)("h1",{id:"grpc-tutorial-introduction",style:{position:"relative"}},(0,i.kt)("a",{parentName:"h1",href:"#grpc-tutorial-introduction","aria-label":"grpc tutorial introduction permalink",className:"anchor before"},(0,i.kt)("svg",{parentName:"a","aria-hidden":"true",focusable:"false",height:"16",version:"1.1",viewBox:"0 0 16 16",width:"16"},(0,i.kt)("path",{parentName:"svg",fillRule:"evenodd",d:"M4 9h1v1H4c-1.5 0-3-1.69-3-3.5S2.55 3 4 3h4c1.45 0 3 1.69 3 3.5 0 1.41-.91 2.72-2 3.25V8.59c.58-.45 1-1.27 1-2.09C10 5.22 8.98 4 8 4H4c-.98 0-2 1.22-2 2.5S3 9 4 9zm9-3h-1v1h1c1 0 2 1.22 2 2.5S13.98 12 13 12H9c-.98 0-2-1.22-2-2.5 0-.83.42-1.64 1-2.09V6.25c-1.09.53-2 1.84-2 3.25C6 11.31 7.55 13 9 13h4c1.45 0 3-1.69 3-3.5S14.5 6 13 6z"}))),"gRPC tutorial introduction"),(0,i.kt)("h6",{className:"inlinePageToc",role:"navigation"},"Table of contents"),(0,i.kt)("ul",null,(0,i.kt)("li",{parentName:"ul"},(0,i.kt)("a",{parentName:"li",href:"#background"},"Background")),(0,i.kt)("li",{parentName:"ul"},(0,i.kt)("a",{parentName:"li",href:"#assumptions"},"Assumptions")),(0,i.kt)("li",{parentName:"ul"},(0,i.kt)("a",{parentName:"li",href:"#prerequisites"},"Prerequisites")),(0,i.kt)("li",{parentName:"ul"},(0,i.kt)("a",{parentName:"li",href:"#sample-service"},"Sample service")),(0,i.kt)("li",{parentName:"ul"},(0,i.kt)("a",{parentName:"li",href:"#build-and-run-sample-service"},"Build and run sample service")),(0,i.kt)("li",{parentName:"ul"},(0,i.kt)("a",{parentName:"li",href:"#try-writing-blog-service-yourself"},"Try writing blog service yourself"))),(0,i.kt)("p",null,"In this tutorial, you'll learn how to build a ",(0,i.kt)("a",{parentName:"p",href:"https://grpc.io/"},"gRPC")," service with Armeria.\nThis tutorial is based on a ",(0,i.kt)("a",{parentName:"p",href:"#sample-service"},"sample service"),", a minimal blog service, with which you can create, read, update, and delete blog posts."),(0,i.kt)("p",null,"Follow this tutorial to write a service yourself or try ",(0,i.kt)("a",{parentName:"p",href:"#build-and-run-sample-service"},"running the sample service")," right away."),(0,i.kt)("h2",{id:"background",style:{position:"relative"}},(0,i.kt)("a",{parentName:"h2",href:"#background","aria-label":"background permalink",className:"anchor before"},(0,i.kt)("svg",{parentName:"a","aria-hidden":"true",focusable:"false",height:"16",version:"1.1",viewBox:"0 0 16 16",width:"16"},(0,i.kt)("path",{parentName:"svg",fillRule:"evenodd",d:"M4 9h1v1H4c-1.5 0-3-1.69-3-3.5S2.55 3 4 3h4c1.45 0 3 1.69 3 3.5 0 1.41-.91 2.72-2 3.25V8.59c.58-.45 1-1.27 1-2.09C10 5.22 8.98 4 8 4H4c-.98 0-2 1.22-2 2.5S3 9 4 9zm9-3h-1v1h1c1 0 2 1.22 2 2.5S13.98 12 13 12H9c-.98 0-2-1.22-2-2.5 0-.83.42-1.64 1-2.09V6.25c-1.09.53-2 1.84-2 3.25C6 11.31 7.55 13 9 13h4c1.45 0 3-1.69 3-3.5S14.5 6 13 6z"}))),"Background"),(0,i.kt)("p",null,"Before we get our hands on the tutorial, let's swiftly go over Armeria's gRPC features.\nArmeria supports features that the upstream (gRPC-Java) does not support."),(0,i.kt)("ul",null,(0,i.kt)("li",{parentName:"ul"},"Protocol",(0,i.kt)("ul",{parentName:"li"},(0,i.kt)("li",{parentName:"ul"},"HTTP/1.1"),(0,i.kt)("li",{parentName:"ul"},"HTTP/2 (upstream)"))),(0,i.kt)("li",{parentName:"ul"},"Serialization format - Framed",(0,i.kt)("ul",{parentName:"li"},(0,i.kt)("li",{parentName:"ul"},"gRPC protobuf: application/grpc+proto (upstream)"),(0,i.kt)("li",{parentName:"ul"},"gRPC JSON: application/grpc+json"),(0,i.kt)("li",{parentName:"ul"},"gRPC Web: application/grpc-web+proto"),(0,i.kt)("li",{parentName:"ul"},"gRPC Web JSON: application/grpc-web+json"),(0,i.kt)("li",{parentName:"ul"},"gRPC Web Text: application/grpc-web-text+proto"))),(0,i.kt)("li",{parentName:"ul"},"Serialization format - Unframed",(0,i.kt)("ul",{parentName:"li"},(0,i.kt)("li",{parentName:"ul"},"Protobuf: application/protobuf"),(0,i.kt)("li",{parentName:"ul"},"JSON: application/json"))),(0,i.kt)("li",{parentName:"ul"},(0,i.kt)("a",{parentName:"li",href:"/docs/server-grpc#decorating-a-grpcservice"},"HTTP level decorator")),(0,i.kt)("li",{parentName:"ul"},"Richer error handling"),(0,i.kt)("li",{parentName:"ul"},(0,i.kt)("a",{parentName:"li",href:"https://google.aip.dev/127"},"HTTP-to-JSON transcoding")),(0,i.kt)("li",{parentName:"ul"},"Customizing service method paths"),(0,i.kt)("li",{parentName:"ul"},(0,i.kt)("a",{parentName:"li",href:"/docs/server-docservice"},"gRPC documentation service")),(0,i.kt)("li",{parentName:"ul"},"gRPC status monitoring with ",(0,i.kt)("a",{parentName:"li",href:"type://MetricCollectingService:https://javadoc.io/doc/com.linecorp.armeria/armeria-javadoc/latest/com/linecorp/armeria/server/metric/MetricCollectingService.html"},"type://MetricCollectingService"))),(0,i.kt)("h2",{id:"assumptions",style:{position:"relative"}},(0,i.kt)("a",{parentName:"h2",href:"#assumptions","aria-label":"assumptions permalink",className:"anchor before"},(0,i.kt)("svg",{parentName:"a","aria-hidden":"true",focusable:"false",height:"16",version:"1.1",viewBox:"0 0 16 16",width:"16"},(0,i.kt)("path",{parentName:"svg",fillRule:"evenodd",d:"M4 9h1v1H4c-1.5 0-3-1.69-3-3.5S2.55 3 4 3h4c1.45 0 3 1.69 3 3.5 0 1.41-.91 2.72-2 3.25V8.59c.58-.45 1-1.27 1-2.09C10 5.22 8.98 4 8 4H4c-.98 0-2 1.22-2 2.5S3 9 4 9zm9-3h-1v1h1c1 0 2 1.22 2 2.5S13.98 12 13 12H9c-.98 0-2-1.22-2-2.5 0-.83.42-1.64 1-2.09V6.25c-1.09.53-2 1.84-2 3.25C6 11.31 7.55 13 9 13h4c1.45 0 3-1.69 3-3.5S14.5 6 13 6z"}))),"Assumptions"),(0,i.kt)("p",null,"This tutorial assumes that you have:"),(0,i.kt)("ul",null,(0,i.kt)("li",{parentName:"ul"},"Experience in building services in Java"),(0,i.kt)("li",{parentName:"ul"},"Experience in Java frameworks for server-side programming"),(0,i.kt)("li",{parentName:"ul"},"Understanding of gRPC and experience in implementing gRPC services")),(0,i.kt)("h2",{id:"prerequisites",style:{position:"relative"}},(0,i.kt)("a",{parentName:"h2",href:"#prerequisites","aria-label":"prerequisites permalink",className:"anchor before"},(0,i.kt)("svg",{parentName:"a","aria-hidden":"true",focusable:"false",height:"16",version:"1.1",viewBox:"0 0 16 16",width:"16"},(0,i.kt)("path",{parentName:"svg",fillRule:"evenodd",d:"M4 9h1v1H4c-1.5 0-3-1.69-3-3.5S2.55 3 4 3h4c1.45 0 3 1.69 3 3.5 0 1.41-.91 2.72-2 3.25V8.59c.58-.45 1-1.27 1-2.09C10 5.22 8.98 4 8 4H4c-.98 0-2 1.22-2 2.5S3 9 4 9zm9-3h-1v1h1c1 0 2 1.22 2 2.5S13.98 12 13 12H9c-.98 0-2-1.22-2-2.5 0-.83.42-1.64 1-2.09V6.25c-1.09.53-2 1.84-2 3.25C6 11.31 7.55 13 9 13h4c1.45 0 3-1.69 3-3.5S14.5 6 13 6z"}))),"Prerequisites"),(0,i.kt)("p",null,"To run and develop the sample service, you need JDK 11 or higher."),(0,i.kt)("h2",{id:"sample-service",style:{position:"relative"}},(0,i.kt)("a",{parentName:"h2",href:"#sample-service","aria-label":"sample service permalink",className:"anchor before"},(0,i.kt)("svg",{parentName:"a","aria-hidden":"true",focusable:"false",height:"16",version:"1.1",viewBox:"0 0 16 16",width:"16"},(0,i.kt)("path",{parentName:"svg",fillRule:"evenodd",d:"M4 9h1v1H4c-1.5 0-3-1.69-3-3.5S2.55 3 4 3h4c1.45 0 3 1.69 3 3.5 0 1.41-.91 2.72-2 3.25V8.59c.58-.45 1-1.27 1-2.09C10 5.22 8.98 4 8 4H4c-.98 0-2 1.22-2 2.5S3 9 4 9zm9-3h-1v1h1c1 0 2 1.22 2 2.5S13.98 12 13 12H9c-.98 0-2-1.22-2-2.5 0-.83.42-1.64 1-2.09V6.25c-1.09.53-2 1.84-2 3.25C6 11.31 7.55 13 9 13h4c1.45 0 3-1.69 3-3.5S14.5 6 13 6z"}))),"Sample service"),(0,i.kt)("p",null,"The ",(0,i.kt)("a",{parentName:"p",href:"https://github.com/line/armeria-examples/tree/main/tutorials/grpc"},"sample service")," provides implementations of CRUD operations as specified below."),(0,i.kt)("table",null,(0,i.kt)("thead",{parentName:"table"},(0,i.kt)("tr",{parentName:"thead"},(0,i.kt)("th",{parentName:"tr",align:null},"Operation"),(0,i.kt)("th",{parentName:"tr",align:null},"Method"))),(0,i.kt)("tbody",{parentName:"table"},(0,i.kt)("tr",{parentName:"tbody"},(0,i.kt)("td",{parentName:"tr",align:null},"Create"),(0,i.kt)("td",{parentName:"tr",align:null},(0,i.kt)("inlineCode",{parentName:"td"},"createBlogPost()"))),(0,i.kt)("tr",{parentName:"tbody"},(0,i.kt)("td",{parentName:"tr",align:null},"Read"),(0,i.kt)("td",{parentName:"tr",align:null},(0,i.kt)("inlineCode",{parentName:"td"},"getBlogPost()"),", ",(0,i.kt)("inlineCode",{parentName:"td"},"listBlogPosts()"))),(0,i.kt)("tr",{parentName:"tbody"},(0,i.kt)("td",{parentName:"tr",align:null},"Update"),(0,i.kt)("td",{parentName:"tr",align:null},(0,i.kt)("inlineCode",{parentName:"td"},"updateBlogPost()"))),(0,i.kt)("tr",{parentName:"tbody"},(0,i.kt)("td",{parentName:"tr",align:null},"Delete"),(0,i.kt)("td",{parentName:"tr",align:null},(0,i.kt)("inlineCode",{parentName:"td"},"deleteBlogPost()"))))),(0,i.kt)("p",null,"The sample service code consists of the following folders and files."),(0,i.kt)("pre",null,(0,i.kt)("code",{parentName:"pre"},"grpc/\n├─ src/\n│  ├─ main/\n│  │  ├─ java/\n│  │  │  ├─ example.armeria.server.blog.grpc/\n│  │  │  │  ├─ BlogNotFoundException.java\n│  │  │  │  ├─ BlogService.java\n│  │  │  │  ├─ GrpcExceptionHandler.java\n│  │  │  │  └─ Main.java\n│  │  ├─ proto/\n│  │  │  └─ blog.proto\n│  └─ test/\n│     └─ java/\n│        └─ example.armeria.server.blog.grpc/\n│           └─ BlogServiceTest.java\n└─ build.gradle\n")),(0,i.kt)(c,{mdxType:"Tip"},(0,i.kt)("p",null,"  To keep our focus on Armeria, this tutorial and the sample service implement memory-based operations instead of using a database.")),(0,i.kt)("h2",{id:"build-and-run-sample-service",style:{position:"relative"}},(0,i.kt)("a",{parentName:"h2",href:"#build-and-run-sample-service","aria-label":"build and run sample service permalink",className:"anchor before"},(0,i.kt)("svg",{parentName:"a","aria-hidden":"true",focusable:"false",height:"16",version:"1.1",viewBox:"0 0 16 16",width:"16"},(0,i.kt)("path",{parentName:"svg",fillRule:"evenodd",d:"M4 9h1v1H4c-1.5 0-3-1.69-3-3.5S2.55 3 4 3h4c1.45 0 3 1.69 3 3.5 0 1.41-.91 2.72-2 3.25V8.59c.58-.45 1-1.27 1-2.09C10 5.22 8.98 4 8 4H4c-.98 0-2 1.22-2 2.5S3 9 4 9zm9-3h-1v1h1c1 0 2 1.22 2 2.5S13.98 12 13 12H9c-.98 0-2-1.22-2-2.5 0-.83.42-1.64 1-2.09V6.25c-1.09.53-2 1.84-2 3.25C6 11.31 7.55 13 9 13h4c1.45 0 3-1.69 3-3.5S14.5 6 13 6z"}))),"Build and run sample service"),(0,i.kt)("p",null,"The sample service provides you implementations of CRUD operations with corresponding service methods.\nHave a go at running the sample gRPC service and see the outcome of this tutorial.\nUsing Armeria's ",(0,i.kt)("a",{parentName:"p",href:"/docs/server-docservice"},"Documentation Service"),", you can easily verify a server is running, receiving requests and sending responses."),(0,i.kt)("ol",null,(0,i.kt)("li",{parentName:"ol"},"Download the code from ",(0,i.kt)("a",{parentName:"li",href:"https://github.com/line/armeria-examples/tree/main/tutorials/grpc"},"here"),"."),(0,i.kt)("li",{parentName:"ol"},"Build the sample service using the Gradle Wrapper.",(0,i.kt)("pre",{parentName:"li"},(0,i.kt)("code",{parentName:"pre",className:"language-bash"},"$ ./gradlew build\n"))),(0,i.kt)("li",{parentName:"ol"},"Run the sample service again, using the Gradle Wrapper.",(0,i.kt)("pre",{parentName:"li"},(0,i.kt)("code",{parentName:"pre",className:"language-bash"},"$ ./gradlew run\n"))),(0,i.kt)("li",{parentName:"ol"},"Open the Documentation service page on your web browser at ",(0,i.kt)("a",{parentName:"li",href:"http://127.0.0.1:8080/docs"},"http://127.0.0.1:8080/docs"),".")),(0,i.kt)("h2",{id:"try-writing-blog-service-yourself",style:{position:"relative"}},(0,i.kt)("a",{parentName:"h2",href:"#try-writing-blog-service-yourself","aria-label":"try writing blog service yourself permalink",className:"anchor before"},(0,i.kt)("svg",{parentName:"a","aria-hidden":"true",focusable:"false",height:"16",version:"1.1",viewBox:"0 0 16 16",width:"16"},(0,i.kt)("path",{parentName:"svg",fillRule:"evenodd",d:"M4 9h1v1H4c-1.5 0-3-1.69-3-3.5S2.55 3 4 3h4c1.45 0 3 1.69 3 3.5 0 1.41-.91 2.72-2 3.25V8.59c.58-.45 1-1.27 1-2.09C10 5.22 8.98 4 8 4H4c-.98 0-2 1.22-2 2.5S3 9 4 9zm9-3h-1v1h1c1 0 2 1.22 2 2.5S13.98 12 13 12H9c-.98 0-2-1.22-2-2.5 0-.83.42-1.64 1-2.09V6.25c-1.09.53-2 1.84-2 3.25C6 11.31 7.55 13 9 13h4c1.45 0 3-1.69 3-3.5S14.5 6 13 6z"}))),"Try writing blog service yourself"),(0,i.kt)("p",null,"Use the sample service's ",(0,i.kt)("a",{parentName:"p",href:"https://github.com/line/armeria-examples/blob/main/tutorials/grpc/build.gradle"},"build.gradle")," file to start building the service from scratch.\nBelow is a part of the ",(0,i.kt)("inlineCode",{parentName:"p"},"build.gradle")," file for the sample service.\nThis tutorial uses ",(0,i.kt)("a",{parentName:"p",href:"https://github.com/google/protobuf-gradle-plugin"},"protobuf-gradle-plugin")," to generate stubs from ",(0,i.kt)("inlineCode",{parentName:"p"},"proto")," files."),(0,i.kt)(u,{language:"groovy",filename:"build.gradle",mdxType:"CodeBlock"},"\napply plugin: 'java'\napply plugin: 'idea'\napply plugin: 'eclipse'\n\nrepositories {\n  mavenCentral()\n}\n\ndependencies {\n  implementation \"com.linecorp.armeria:armeria:"+l["com.linecorp.armeria:armeria-bom"]+'"\n\n  implementation "com.linecorp.armeria:armeria-grpc:'+l["com.linecorp.armeria:armeria-bom"]+'"\n\n  // Logging\n  runtimeOnly "ch.qos.logback:logback-classic:'+l["ch.qos.logback:logback-classic"]+'"\n\n  testImplementation "org.junit.jupiter:junit-jupiter:'+l["org.junit:junit-bom"]+'"\n\n  testImplementation "com.linecorp.armeria:armeria-junit5:'+l["com.linecorp.armeria:armeria-bom"]+'"\n\n  testImplementation "org.assertj:assertj-core:'+l["org.assertj:assertj-core"]+'"\n}\n'),(0,i.kt)("p",null,"Start writing the blog service yourself by following the tutorial step by step:"),(0,i.kt)("ol",null,(0,i.kt)("li",{parentName:"ol"},(0,i.kt)("a",{parentName:"li",href:"/tutorials/grpc/blog/define-service"},"Define a service")),(0,i.kt)("li",{parentName:"ol"},(0,i.kt)("a",{parentName:"li",href:"/tutorials/grpc/blog/run-service"},"Run a service")),(0,i.kt)("li",{parentName:"ol"},(0,i.kt)("a",{parentName:"li",href:"/tutorials/grpc/blog/implement-create"},"Implement CREATE")),(0,i.kt)("li",{parentName:"ol"},(0,i.kt)("a",{parentName:"li",href:"/tutorials/grpc/blog/implement-read"},"Implement READ")),(0,i.kt)("li",{parentName:"ol"},(0,i.kt)("a",{parentName:"li",href:"/tutorials/grpc/blog/implement-update"},"Implement UPDATE")),(0,i.kt)("li",{parentName:"ol"},(0,i.kt)("a",{parentName:"li",href:"/tutorials/grpc/blog/implement-delete"},"Implement DELETE"))))}h.isMDXComponent=!0},89791:function(e,t,a){a.d(t,{Z:function(){return o}});var r=a(25444),i=a(67294),n=JSON.parse('{"root":["index"],"Useful links":{"User manual":"/docs","API documentation":"https://javadoc.io/doc/com.linecorp.armeria/armeria-javadoc/latest/index.html","Release notes":"/release-notes"},"REST service":["rest/blog","rest/blog/create-server","rest/blog/prepare-data-object","rest/blog/add-services-to-server","rest/blog/implement-create","rest/blog/implement-read","rest/blog/implement-update","rest/blog/implement-delete"],"gRPC service":["grpc/blog","grpc/blog/define-service","grpc/blog/run-service","grpc/blog/implement-create","grpc/blog/implement-read","grpc/blog/implement-update","grpc/blog/implement-delete"],"Thrift service":["thrift/blog","thrift/blog/define-service","thrift/blog/run-service","thrift/blog/implement-create","thrift/blog/implement-read","thrift/blog/implement-update","thrift/blog/implement-delete"]}'),l=a(46731),o=function(e){var t=(0,r.useStaticQuery)("3172452987").allMdx.nodes;return i.createElement(l.Z,Object.assign({},e,{candidateMdxNodes:t,index:n,prefix:"tutorials",menuTitle:!0,pageTitleSuffix:"Armeria tutorial"}))}}}]);
//# sourceMappingURL=component---src-pages-tutorials-grpc-blog-index-mdx-926e1df9f671f1f32206.js.map