import { RouteComponentProps } from '@reach/router';
import { Button, Carousel, Tooltip, Typography } from 'antd';
import { graphql, Link, useStaticQuery } from 'gatsby';
import { GatsbyImage } from 'gatsby-plugin-image';
import { OutboundLink } from 'gatsby-plugin-google-analytics';
import React from 'react';

import Blockquote from '../components/blockquote';
import BrowserMockup from '../components/browser-mockup';
import CodeBlock from '../components/code-block';
import Emoji from '../components/emoji';
import Logo from '../components/logo';
import { Highlight, Marketing, MarketingBlock } from '../components/marketing';
import NoWrap from '../components/nowrap';
import ProjectBadge from '../components/project-badge';
import BaseLayout from '../layouts/base';

import * as styles from './index.module.less';

const { Title, Paragraph } = Typography;

const IndexPage = (props: RouteComponentProps): JSX.Element => {
  const data = useStaticQuery(graphql`
    query {
      docServiceImages: allFile(
        filter: { relativePath: { glob: "docservice-carousel-*.png" } }
        sort: { fields: relativePath, order: ASC }
      ) {
        nodes {
          childImageSharp {
            gatsbyImageData(layout: FULL_WIDTH)
          }
        }
      }

      logoVideo: file(relativePath: { eq: "armeria.m4v" }) {
        publicURL
      }

      logoGif: file(relativePath: { eq: "armeria.gif" }) {
        publicURL
      }

      integrationDiagram: file(relativePath: { eq: "integration.svg" }) {
        publicURL
      }
    }
  `);

  function renderGetStartedButtons(responsive: boolean) {
    const className = `${styles.getStartedButton} ${
      responsive ? styles.responsive : ''
    }`;
    return (
      <>
        <Link to="/docs" className={className}>
          <Button type="primary" size="large">
            <Emoji text="Learn more ✨" />
          </Button>
        </Link>
        <Link to="/community" className={className}>
          <Button size="large">
            <Emoji text="Community 👋" />
          </Button>
        </Link>
      </>
    );
  }

  return (
    <BaseLayout
      location={props.location}
      pageTitle="Armeria &ndash; Your go-to microservice framework"
      pageDescription={
        'Armeria is your go-to microservice framework for any situation. ' +
        'You can build any type of microservice leveraging your favorite technologies, ' +
        'including gRPC, Thrift, Kotlin, Retrofit, Reactive Streams, Spring Boot and Dropwizard. ' +
        'Brought to you by the creator of Netty and his colleagues at LINE.'
      }
      contentClassName={styles.wrapper}
    >
      <Marketing className={styles.slogan}>
        <MarketingBlock noreveal>
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
          <Title level={1}>
            Build a reactive microservice{' '}
            <NoWrap>
              <Highlight>
                at <u>your</u> pace
              </Highlight>
              ,
            </NoWrap>{' '}
            <NoWrap>not theirs.</NoWrap>
          </Title>
          <Paragraph>
            <em>Armeria</em> is your go-to microservice framework for any
            situation. You can build any type of microservice leveraging your
            favorite technologies, including gRPC, Thrift, Kotlin, Retrofit,
            Reactive Streams, Spring Boot and Dropwizard.
          </Paragraph>
          <Paragraph className={styles.indented}>
            &ldquo; Brought to you by the creator of{' '}
            <Tooltip title="The most popular non-blocking I/O client-server framework in Java ecosystem">
              <OutboundLink href="https://netty.io/">Netty</OutboundLink>
            </Tooltip>{' '}
            and his colleagues at{' '}
            <Tooltip title="The company behind the most popular mobile messaging app in Japan, Taiwan and Thai">
              <OutboundLink href="https://engineering.linecorp.com/en/">
                LINE
              </OutboundLink>
            </Tooltip>{' '}
            &rdquo;
          </Paragraph>
          <div>{renderGetStartedButtons(true)}</div>
        </MarketingBlock>
      </Marketing>
      <Marketing>
        <MarketingBlock>
          <Title level={1}>
            gRPC, Thrift, REST, static files? <NoWrap>You name it.</NoWrap>{' '}
            <Highlight nowrap>We serve them all.</Highlight>
          </Title>
          <Paragraph>
            Let&apos;s embrace the reality &mdash; we almost always have to deal
            with more than one protocol. It was once Thrift, today it&apos;s
            gRPC, and REST never gets old. At the same time, you sometimes have
            to handle health check requests from a load balancer or even serve
            some static files.
          </Paragraph>
          <Paragraph>
            Armeria is capable of running services using different protocols,
            all on a single port. No need for any proxies or sidecars. Enjoy the
            reduced complexity and points of failure, even when migrating
            between protocols!
          </Paragraph>
        </MarketingBlock>
        <MarketingBlock>
          <CodeBlock language="java">
            {`
            Server
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
          <Title level={1}>
            <Highlight>Headache-free RPC</Highlight> with documentation service
          </Title>
          <Paragraph>
            RPC protocols were often more difficult to work with than RESTful
            APIs. How would you make an ad-hoc call to your gRPC service when
            you can&apos;t find the right <code>.proto</code> file? How would
            you help your colleague reproduce your problem by making the same
            call?
          </Paragraph>
          <Paragraph>
            Armeria&apos;s <em>documentation service</em> allows you to browse
            the complete list of services and their documentation from a web
            browser. You can also make a real RPC call in human friendly JSON
            format and share the URL of the call with your colleague. Who said
            RPC is difficult to work with? <Emoji text="😆" />
          </Paragraph>
        </MarketingBlock>
        <MarketingBlock>
          <Carousel
            className={styles.docServiceCarousel}
            autoplay
            autoplaySpeed={5000}
          >
            {/* eslint-disable react/no-array-index-key */}
            {data.docServiceImages.nodes.map((e: any, imageIdx: number) => (
              <BrowserMockup key={imageIdx}>
                <GatsbyImage
                  image={e.childImageSharp.gatsbyImageData}
                  alt="DocService screenshot"
                />
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
              <OutboundLink href="https://github.com/renaudb">
                Renaud Bourassa
              </OutboundLink>
            }
            from={
              <OutboundLink href="https://slack.engineering/">
                Slack
              </OutboundLink>
            }
            bgColor1="rgba(255, 255, 255, 0.5)"
            bgColor2="white"
          >
            Armeria was pivotal in our effort to refactor core components of our
            search system into separate services. Decorators make reusing
            existing components and building new ones a breeze. This helped
            streamline the integration of our new services with our existing
            infrastructure, including logging, monitoring, tracing, service
            discovery, and RPC. The modern Java API is a joy to use and the
            asynchronous architecture delivers great performance with high
            concurrency. The Armeria community has been very welcoming and the
            maintainers were very responsive to our needs during our migration
            process and beyond. We are now running several search and discovery
            services built on Armeria and it has become our framework of choice
            for building new Java services.
          </Blockquote>
        </MarketingBlock>
      </Marketing>
      <Marketing>
        <MarketingBlock>
          <Title level={1}>
            Fuel your request pipeline with{' '}
            <Highlight>reusable components</Highlight>
          </Title>
          <Paragraph>
            There are so many cross-cutting concerns you need to address when
            building a microservice &mdash; metrics, distributed tracing, load
            balancing, authentication, rate-limiting, circuit breakers,
            automatic retries, etc.
          </Paragraph>
          <Paragraph>
            Armeria solves your problems by providing various reusable
            components called &lsquo;decorators&rsquo; in a clean, consistent,
            flexible and user-friendly API. You can also write a custom
            decorator if ours doesn&apos;t work for your use case.
          </Paragraph>
        </MarketingBlock>
        <MarketingBlock>
          <CodeBlock language="java">
            {`
            Server
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
          <Title level={1}>
            <Highlight>Integrate seamlessly</Highlight> with your favorite
            frameworks &amp; languages
          </Title>
          <Paragraph>
            Wanna try some cool technology while using Spring Boot or
            Dropwizard? That&apos;s absolutely fine. Armeria integrates them
            without getting in the way.{' '}
            <span className={styles.hideIfShrunk}>
              <br />
            </span>
            It serves gRPC or Thrift requests at high efficiency while leaving
            other requests to Spring MVC, WebFlux or Dropwizard with minimal
            changes.
          </Paragraph>
          <Paragraph>
            Got a legacy webapp you need to keep running until you migrate off?
            No problem! Armeria can serve any legacy webapps that run on top of
            Tomcat or Jetty until you finish your migration. Adios, maintenance
            hell! <Emoji text="🥂" />
          </Paragraph>
        </MarketingBlock>
        <MarketingBlock>
          <object
            data={data.integrationDiagram.publicURL}
            title="A diagram that shows the integrations Armeria provides"
            style={{ width: '100%' }}
          />
        </MarketingBlock>
      </Marketing>
      <Marketing className={styles.testimonial}>
        <MarketingBlock>
          <Blockquote
            author={
              <OutboundLink href="https://github.com/andrewoma">
                Andrew O&apos;Malley
              </OutboundLink>
            }
            from={
              <OutboundLink href="https://en.wikipedia.org/wiki/Afterpay">
                Afterpay
              </OutboundLink>
            }
            bgColor1="rgba(255, 255, 255, 0.5)"
            bgColor2="white"
            reverse
          >
            Armeria has eased our adoption of gRPC at Afterpay. Serving gRPC,
            documentation, health checks and metrics on a single port (with or
            without TLS) gives us the functionality of a sidecar like Envoy in a
            single process. Automated serving of API documentation, complete
            with sample payloads, allows simple ad-hoc gRPC requests straight
            from the browser. Distributed tracing is trivial to configure using
            the built-in Brave decorators. Logging request bodies, performing
            retries or circuit breaking are all built-in. The friendly devs
            rapidly respond to issues and feature requests. Armeria is becoming
            the default stack for gRPC at Afterpay.
          </Blockquote>
        </MarketingBlock>
      </Marketing>
      <Marketing>
        <MarketingBlock>
          <Title level={1}>
            Flexible <Highlight>service discovery</Highlight> and client-side{' '}
            <Highlight>load-balancing</Highlight>
          </Title>
          <Paragraph>
            Armeria provides various service discovery mechanisms, from
            Kubernetes-style DNS records to ZooKeeper data nodes. Send periodic
            or long-polling pings to exclude unhealthy endpoints autonomously.
            Choose the best load-balancing strategy for you &mdash; weighted
            round-robin, sticky-session, etc. You can even write a custom
            service discovery or load-balancing strategy.
          </Paragraph>
        </MarketingBlock>
        <MarketingBlock>
          <CodeBlock language="java">
            {`
            EndpointGroup group =
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
          <Title level={3}>
            Build your service with
            <video
              className="hideOnReducedMotion"
              src={data.logoVideo.publicURL}
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
                src={data.logoGif.publicURL}
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
          </Title>
          {renderGetStartedButtons(false)}
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
    </BaseLayout>
  );
};

export default IndexPage;
