import React, { type ReactNode } from 'react';
import clsx from 'clsx';
import { Carousel, Tooltip } from 'antd';
import Link from '@docusaurus/Link';
import useBaseUrl from '@docusaurus/useBaseUrl';
import Layout from '@theme/Layout';
import Heading from '@theme/Heading';
import CodeBlock from '@theme/CodeBlock';

import NoWrap from '@site/src/components/nowrap';
import {
  Highlight,
  Marketing,
  MarketingBlock,
} from '@site/src/components/marketing';
import Emoji from '@site/src/components/emoji';
import BrowserMockup from '@site/src/components/browser-mockup';
import Blockquote from '@site/src/components/blockquote';
import Logo from '@site/src/components/logo';
import ProjectBadge from '@site/src/components/project-badge';

import docServiceCarousel1 from '@site/static/img/docservice-carousel-1.png';
import docServiceCarousel2 from '@site/static/img/docservice-carousel-2.png';
import styles from './index.module.css';

const renderGetStartedButtons = (responsive: boolean) => (
  <>
    <Link
      to="/docs"
      className={clsx(
        'button',
        'button--primary',
        'button--lg',
        styles.getStartedButton,
        responsive && styles.responsive,
      )}
    >
      <Emoji text="Learn more ✨" />
    </Link>
    <Link
      to="/community"
      className={clsx(
        'button',
        'button--secondary',
        'button--lg',
        styles.getStartedButton,
        responsive && styles.responsive,
      )}
    >
      <Emoji text="Community 👋" />
    </Link>
  </>
);

const HomepageHeader = () => {
  return (
    <header className={clsx('hero', styles.heroBanner)}>
      <div className="container">
        <Logo
          className={styles.sloganBackgroundImage}
          width="768px"
          height="768px"
          primaryColor="rgba(255, 255, 255, 1.0)"
          secondaryColor="rgba(255, 255, 255, 0.55)"
          tertiaryColor="transparent"
          label=" "
          notext
          ariaHidden
        />
        <Heading as="h1" className="hero__title">
          Build a reactive microservice <br />
          <NoWrap>
            <Highlight>
              at <u>your</u> pace
            </Highlight>
            ,
          </NoWrap>{' '}
          <NoWrap>not theirs.</NoWrap>
        </Heading>
        <p className="hero__subtitle">
          <em>Armeria</em> is your go-to microservice framework for any
          situation. You can build any type of microservice leveraging your
          favorite technologies, including gRPC, Thrift, Kotlin, Retrofit,
          Reactive Streams, Spring Boot and Dropwizard.
        </p>
        <p className={clsx('hero__subtitle', styles.indented)}>
          &ldquo; Brought to you by the creator of{' '}
          <Tooltip title="The most popular non-blocking I/O client-server framework in Java ecosystem">
            <Link href="https://netty.io/">Netty</Link>
          </Tooltip>{' '}
          and his colleagues at{' '}
          <Tooltip title="The company behind LINE and Yahoo! Japan, the leaders in digital communication and web services">
            <Link href="https://en.wikipedia.org/wiki/LY_Corporation">
              LY Corporation
            </Link>
          </Tooltip>{' '}
          &rdquo;
        </p>
        <div>{renderGetStartedButtons(true)}</div>
      </div>
    </header>
  );
};

const Home: React.FC = (): ReactNode => {
  const docServiceImages = [docServiceCarousel1, docServiceCarousel2];
  return (
    <Layout
      title="Armeria - Your go-to microservice framework"
      description={
        'Armeria is your go-to microservice framework for any situation. ' +
        'You can build any type of microservice leveraging your favorite technologies, ' +
        'including gRPC, Thrift, Kotlin, Retrofit, Reactive Streams, Spring Boot and Dropwizard. ' +
        'Brought to you by the creator of Netty and his colleagues at LY Corporation.'
      }
    >
      <HomepageHeader />
      <main>
        <Marketing>
          <MarketingBlock>
            <Heading as="h1">
              gRPC, Thrift, REST, static files? <NoWrap>You name it.</NoWrap>{' '}
              <Highlight nowrap>We serve them all.</Highlight>
            </Heading>
            <p>
              Let&apos;s embrace the reality &mdash; we almost always have to
              deal with more than one protocol. It was once Thrift, today
              it&apos;s gRPC, and REST never gets old. At the same time, you
              sometimes have to handle health check requests from a load
              balancer or even serve some static files.
            </p>
            <p>
              Armeria is capable of running services using different protocols,
              all on a single port. No need for any proxies or sidecars. Enjoy
              the reduced complexity and points of failure, even when migrating
              between protocols!
            </p>
          </MarketingBlock>
          <MarketingBlock>
            <CodeBlock language="java">
              {`Server
  .builder()
  .service(
    "/hello",
    (ctx, req) -> HttpResponse.of("Hello!"))
  .service(GrpcService
    .builder()
    .addService(myGrpcServiceImpl)
    .build())
  .service(
    "/api/thrift",
    ThriftService.of(myThriftServiceImpl))
  .service(
    "prefix:/files",
    FileService.of(new File("/var/www")))
  .service(
    "/monitor/l7check",
    HealthCheckService.of())
  .build()
  .start();
            `}
            </CodeBlock>
          </MarketingBlock>
        </Marketing>
        <Marketing reverse>
          <MarketingBlock>
            <Heading as="h1">
              <Highlight>Headache-free RPC</Highlight> with documentation
              service
            </Heading>
            <p>
              RPC protocols were often more difficult to work with than RESTful
              APIs. How would you make an ad-hoc call to your gRPC service when
              you can&apos;t find the right <code>.proto</code> file? How would
              you help your colleague reproduce your problem by making the same
              call?
            </p>
            <p>
              Armeria&apos;s <em>documentation service</em> allows you to browse
              the complete list of services and their documentation from a web
              browser. You can also make a real RPC call in human friendly JSON
              format and share the URL of the call with your colleague. Who said
              RPC is difficult to work with? <Emoji text="😆" />
            </p>
          </MarketingBlock>
          <MarketingBlock>
            <Carousel
              className={styles.docServiceCarousel}
              autoplay
              autoplaySpeed={30000}
            >
              {/* eslint-disable react/no-array-index-key */}
              {docServiceImages.map((imageSrc: any, imageIdx: number) => (
                <BrowserMockup key={imageIdx}>
                  <img src={imageSrc} alt="DocService screenshot" />
                </BrowserMockup>
              ))}
              {/* eslint-enable react/no-array-index-key */}
            </Carousel>
          </MarketingBlock>
        </Marketing>
        <Marketing className={styles.testimonial}>
          <MarketingBlock>
            <Blockquote
              author={
                <Link to="https://github.com/renaudb">Renaud Bourassa</Link>
              }
              from={<Link to="https://slack.engineering/">Slack</Link>}
              bgColor1="rgba(255, 255, 255, 0.5)"
              bgColor2="white"
            >
              Armeria was pivotal in our effort to refactor core components of
              our search system into separate services. Decorators make reusing
              existing components and building new ones a breeze. This helped
              streamline the integration of our new services with our existing
              infrastructure, including logging, monitoring, tracing, service
              discovery, and RPC. The modern Java API is a joy to use and the
              asynchronous architecture delivers great performance with high
              concurrency. The Armeria community has been very welcoming and the
              maintainers were very responsive to our needs during our migration
              process and beyond. We are now running several search and
              discovery services built on Armeria and it has become our
              framework of choice for building new Java services.
            </Blockquote>
          </MarketingBlock>
        </Marketing>
        <Marketing>
          <MarketingBlock>
            <Heading as="h1">
              Fuel your request pipeline with{' '}
              <Highlight>reusable components</Highlight>
            </Heading>
            <p>
              There are so many cross-cutting concerns you need to address when
              building a microservice &mdash; metrics, distributed tracing, load
              balancing, authentication, rate-limiting, circuit breakers,
              automatic retries, etc.
            </p>
            <p>
              Armeria solves your problems by providing various reusable
              components called &lsquo;decorators&rsquo; in a clean, consistent,
              flexible and user-friendly API. You can also write a custom
              decorator if ours doesn&apos;t work for your use case.
            </p>
          </MarketingBlock>
          <MarketingBlock>
            <CodeBlock language="java">
              {`Server
  .builder()
  .service((ctx, req) -> ...)
  .decorator(
    MetricCollectingService.newDecorator(...))
  .decorator(
    BraveService.newDecorator(...))
  .decorator(
    AuthService.newDecorator(...))
  .decorator(
    ThrottlingService.newDecorator(...))
  .build()
  .start();
`}
            </CodeBlock>
          </MarketingBlock>
        </Marketing>
        <Marketing reverse>
          <MarketingBlock>
            <Heading as="h1">
              <Highlight>Integrate seamlessly</Highlight> with your favorite
              frameworks &amp; languages
            </Heading>
            <p>
              Wanna try some cool technology while using Spring Boot or
              Dropwizard? That&apos;s absolutely fine. Armeria integrates them
              without getting in the way.{' '}
              <span className={styles.hideIfShrunk}>
                <br />
              </span>
              It serves gRPC or Thrift requests at high efficiency while leaving
              other requests to Spring MVC, WebFlux or Dropwizard with minimal
              changes.
            </p>
            <p>
              Got a legacy webapp you need to keep running until you migrate
              off? No problem! Armeria can serve any legacy webapps that run on
              top of Tomcat or Jetty until you finish your migration. Adios,
              maintenance hell! <Emoji text="🥂" />
            </p>
          </MarketingBlock>
          <MarketingBlock>
            <object
              data={useBaseUrl('/img/integration.svg')}
              title="A diagram that shows the integrations Armeria provides"
              style={{ width: '100%' }}
            />
          </MarketingBlock>
        </Marketing>
        <Marketing className={styles.testimonial}>
          <MarketingBlock>
            <Blockquote
              author={
                <Link to="https://github.com/andrewoma">
                  Andrew O&apos;Malley
                </Link>
              }
              from={
                <Link to="https://en.wikipedia.org/wiki/Afterpay">
                  Afterpay
                </Link>
              }
              bgColor1="rgba(255, 255, 255, 0.5)"
              bgColor2="white"
              reverse
            >
              Armeria has eased our adoption of gRPC at Afterpay. Serving gRPC,
              documentation, health checks and metrics on a single port (with or
              without TLS) gives us the functionality of a sidecar like Envoy in
              a single process. Automated serving of API documentation, complete
              with sample payloads, allows simple ad-hoc gRPC requests straight
              from the browser. Distributed tracing is trivial to configure
              using the built-in Brave decorators. Logging request bodies,
              performing retries or circuit breaking are all built-in. The
              friendly devs rapidly respond to issues and feature requests.
              Armeria is becoming the default stack for gRPC at Afterpay.
            </Blockquote>
          </MarketingBlock>
        </Marketing>
        <Marketing>
          <MarketingBlock>
            <Heading as="h1">
              Flexible <Highlight>service discovery</Highlight> and client-side{' '}
              <Highlight>load-balancing</Highlight>
            </Heading>
            <p>
              Armeria provides various service discovery mechanisms, from
              Kubernetes-style DNS records to ZooKeeper data nodes. Send
              periodic or long-polling pings to exclude unhealthy endpoints
              autonomously. Choose the best load-balancing strategy for you
              &mdash; weighted round-robin, sticky-session, etc. You can even
              write a custom service discovery or load-balancing strategy.
            </p>
          </MarketingBlock>
          <MarketingBlock>
            <CodeBlock language="java">
              {`EndpointGroup group =
  DnsAddressEndpointGroup.of(
    "k8s.default.svc.cluster.local.");

EndpointGroup healthyGroup =
  HealthCheckedEndpointGroup.of(
    group,
    "/monitor/l7check");

WebClient client =
  WebClient.of("h2c", healthyGroup);

client.get("/path");
`}
            </CodeBlock>
          </MarketingBlock>
        </Marketing>
        <Marketing className={styles.getStarted}>
          <MarketingBlock>
            <Heading as="h3">
              Build your service with
              <video
                className="hideOnReducedMotion"
                src={useBaseUrl('/img/armeria.m4v')}
                title="Armeria"
                width="160"
                height="64"
                preload="none"
                autoPlay
                muted
                loop
                style={{
                  position: 'relative',
                  top: '1.4rem',
                  margin: '0 0.6rem',
                }}
              >
                <img
                  src={useBaseUrl('/img/armeria.gif')}
                  loading="lazy"
                  width="160"
                  height="64"
                  alt="Armeria"
                />
              </video>
              <Logo
                className="showOnReducedMotion"
                width="10.5rem"
                textColor="#ff0089"
                style={{
                  position: 'relative',
                  top: '-0.18rem',
                  marginLeft: '0.3rem',
                  marginRight: '0.35rem',
                }}
                label="Armeria"
              />
              today!
            </Heading>
            <div>{renderGetStartedButtons(false)}</div>
            <div className={styles.badges}>
              <ProjectBadge
                url={`https://img.shields.io/github/contributors/line/armeria.svg?link=${encodeURIComponent(
                  'https://github.com/line/armeria/graphs/contributors',
                )}`}
              />
              <ProjectBadge
                url={`https://img.shields.io/github/commit-activity/m/line/armeria.svg?label=commits&link=${encodeURIComponent(
                  'https://github.com/line/armeria/pulse',
                )}`}
              />
              <ProjectBadge
                url={`https://img.shields.io/maven-central/v/com.linecorp.armeria/armeria.svg?label=version&link=${encodeURIComponent(
                  'https://search.maven.org/search?q=g:com.linecorp.armeria%20AND%20a:armeria',
                )}`}
              />
              <ProjectBadge
                url={`https://img.shields.io/github/release-date/line/armeria.svg?label=release&link=${encodeURIComponent(
                  'https://github.com/line/armeria/commits',
                )}`}
              />
            </div>
          </MarketingBlock>
        </Marketing>
      </main>
    </Layout>
  );
};

export default Home;
