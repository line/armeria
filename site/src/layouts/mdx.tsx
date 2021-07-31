import { GithubOutlined, LeftOutlined, RightOutlined } from '@ant-design/icons';
import loadable from '@loadable/component';
import { MDXProvider } from '@mdx-js/react';
import { RouteComponentProps } from '@reach/router';
import { Button, Layout, Tabs as AntdTabs, Typography } from 'antd';
import { Link, withPrefix } from 'gatsby';
import { OutboundLink } from 'gatsby-plugin-google-analytics';
import React, { useLayoutEffect } from 'react';
import StickyBox from 'react-sticky-box';
import tocbot from 'tocbot';

import 'antd/es/table/style';

import { Tip, Warning } from '../components/alert';
import { TypeLink } from '../components/api-link';
import AspectRatio from '../components/aspect-ratio';
import CodeBlock from '../components/code-block';
import Emoji from '../components/emoji';
import Mailchimp from '../components/mailchimp';
import MaxWidth from '../components/max-width';
import NoWrap from '../components/nowrap';
import RequiredDependencies from '../components/required-dependencies';
import TutorialSteps from '../components/steps';
import BaseLayout from './base';
import pagePath from './page-path';
import styles from './mdx.module.less';

const { Content } = Layout;
const { Paragraph, Title } = Typography;

interface MdxLayoutProps extends RouteComponentProps {
  pageContext: any;
  candidateMdxNodes: any[];
  index: { [section: string]: string[] | { [title: string]: string } };
  prefix: string;
  pageTitle: string;
  pageTitleSuffix: string;
  showPrevNextButton?: boolean;
  noEdit?: boolean;
  menuTitle?: boolean;
}

const pathPrefix = withPrefix('/');

// Use our CodeBlock component for <a> and <pre>.
const mdxComponents: any = {
  a: (props: any) => {
    const href: string = `${props.href || ''}`;
    if (href.startsWith('type://') || href.startsWith('typeplural://')) {
      const prefixLength = href.indexOf('://') + 3;
      const delimiterIndex = href.indexOf(':', prefixLength);
      const [typeName, actualHref] =
        delimiterIndex >= 0
          ? [
              href.substring(prefixLength, delimiterIndex),
              href.substring(delimiterIndex + 1),
            ]
          : [href.substring(prefixLength), undefined];
      return (
        <TypeLink
          name={typeName}
          href={actualHref}
          plural={href.startsWith('typeplural://')}
        />
      );
    }

    if (href.includes('://') || href.startsWith('//')) {
      return <OutboundLink {...props} />;
    }

    if (href.startsWith(pathPrefix)) {
      // Strip the path prefix when passing to <Link />
      // because it will prepend the path prefix.
      return (
        <Link
          to={props.href.substring(pathPrefix.length - 1)}
          className={props.className}
        >
          {props.children}
        </Link>
      );
    }

    // eslint-disable-next-line jsx-a11y/anchor-has-content
    return <a {...props} />;
  },
  pre: (props: any) => {
    const language =
      props.children.props.className?.replace(/language-/, '') || 'none';
    return (
      <CodeBlock
        language={language}
        filename={props.children.props.filename}
        highlight={props.children.props.highlight}
        showlineno={props.children.props.showlineno}
      >
        {props.children.props.children}
      </CodeBlock>
    );
  },
  h1: (props: any) => <Title level={1} {...props} />,
  h2: (props: any) => <Title level={2} {...props} />,
  h3: (props: any) => <Title level={3} {...props} />,
  h4: (props: any) => <Title level={4} {...props} />,
  table: (props: any) => {
    return (
      <div className="ant-table ant-table-small ant-table-bordered">
        <div className="ant-table-container">
          <div className="ant-table-content">
            <table {...props} />
          </div>
        </div>
      </div>
    );
  },
  thead: (props: any) => {
    return <thead className="ant-table-thead" {...props} />;
  },
  tbody: (props: any) => {
    return <tbody className="ant-table-tbody" {...props} />;
  },
  tfoot: (props: any) => {
    return <tfoot className="ant-table-tfoot" {...props} />;
  },
  th: (props: any) => {
    return <th className="ant-table-cell" {...filterTableCellProps(props)} />;
  },
  td: (props: any) => {
    return <td className="ant-table-cell" {...filterTableCellProps(props)} />;
  },
  AspectRatio,
  CodeBlock,
  Emoji,
  Mailchimp,
  MaxWidth,
  NoWrap,
  RequiredDependencies,
  TutorialSteps,
  Tabs: (props: any) => {
    return <AntdTabs animated={{ inkBar: true, tabPane: false }} {...props} />;
  },
  TabPane: AntdTabs.TabPane,
  Tip,
  TypeLink,
  ThankYou: loadable(() => import('../components/thank-you')),
  Warning,
};

function filterTableCellProps(props: any) {
  const newProps = {
    ...props,
    rowSpan: props.rowspan,
    colSpan: props.colspan,
  };
  if (props.align) {
    if (newProps.style) {
      newProps.style = { ...newProps.style, textAlign: props.align };
    } else {
      newProps.style = { textAlign: props.align };
    }
  }

  delete newProps.align;
  delete newProps.rowspan;
  delete newProps.colspan;
  return newProps;
}

const MdxLayout: React.FC<MdxLayoutProps> = (props) => {
  useLayoutEffect(() => {
    tocbot.init({
      tocSelector: `.${styles.pageToc}`,
      contentSelector: `.${styles.content}`,
      headingSelector: 'h1, h2, h3, h4',
      ignoreHiddenElements: true,
    });

    return () => {
      tocbot.destroy();
    };
  }, []);

  // Create a map of page name and MDX node pair, while adding the 'href' property.
  const nameToMdxNode: { [name: string]: any } = {};
  props.candidateMdxNodes.forEach((mdxNode: any) => {
    if (
      mdxNode.parent.sourceInstanceName === props.prefix &&
      (mdxNode.tableOfContents.items?.length || 0) > 0
    ) {
      /* eslint-disable no-param-reassign */
      mdxNode.isBookmark = false;
      if (mdxNode.parent.name === 'index') {
        mdxNode.href = `/${props.prefix}`;
      } else if (typeof mdxNode.parent.relativeDirectory === 'undefined') {
        mdxNode.href = `/${props.prefix}/${mdxNode.parent.name}`;
      } else {
        mdxNode.href = `/${props.prefix}/${mdxNode.parent.relativeDirectory}/${mdxNode.parent.name}`;
      }
      /* eslint-enable no-param-reassign */
      nameToMdxNode[mdxNode.parent.name] = mdxNode;
    }
  });

  // Create a list of MDX pages, ordered as specified in 'index'.
  const mdxNodes: any[] = [];
  const groupToMdxNodes: { [group: string]: any[] } = {};
  let prevMdxNode: any;
  Object.entries(props.index).forEach(
    ([groupName, mdxNodeNamesOrBookmarks]) => {
      if (Array.isArray(mdxNodeNamesOrBookmarks)) {
        const mdxNodeNames = mdxNodeNamesOrBookmarks;
        for (let i = 0; i < mdxNodeNames.length; i += 1) {
          const mdxNodeName = mdxNodeNames[i];
          const mdxNode = nameToMdxNode[mdxNodeName];
          if (!mdxNode) {
            continue;
          }
          mdxNodes.push(mdxNode);

          if (prevMdxNode) {
            // Note: Do not refer to 'prevMdxNode' or 'mdxNode' directly here,
            //       to avoid creating cyclic references.
            mdxNode.prevNodeName = prevMdxNode.parent.name;
            prevMdxNode.nextNodeName = mdxNodeName;
          }
          prevMdxNode = mdxNode;

          // Group MDX nodes by its group.
          const groupedMdxNodes = groupToMdxNodes[groupName];
          if (groupedMdxNodes) {
            groupedMdxNodes.push(mdxNode);
          } else {
            groupToMdxNodes[groupName] = [mdxNode];
          }
        }
      } else {
        const bookmarks = mdxNodeNamesOrBookmarks;
        Object.entries(bookmarks).forEach(([bookmarkTitle, bookmarkUrl]) => {
          // Not really an MDX node, but we fake it.
          const mdxNode = {
            isBookmark: true,
            href: bookmarkUrl,
            tableOfContents: {
              items: [
                {
                  title: bookmarkTitle,
                },
              ],
            },
          };

          // Add the fake MDX node to its group, so it appears on the ToC.
          // Note that we do not add it to mdxNodes because mdxNodes is used for
          // generating prev/next buttons.
          const groupedMdxNodes = groupToMdxNodes[groupName];
          if (groupedMdxNodes) {
            groupedMdxNodes.push(mdxNode);
          } else {
            groupToMdxNodes[groupName] = [mdxNode];
          }
        });
      }
    },
  );

  const currentMdxNode = findCurrentMdxNode();

  // Generate some properties required for rendering.
  const pageTitle = `${props.pageTitle} — ${props.pageTitleSuffix}`;
  const pageDescription = currentMdxNode?.excerpt
    ?.replace(/\w+:\/\//g, '')
    .replace(/\s+(\W)/g, '$1')
    .replace(/(?:\s|\r|\n)+/g, ' ');
  const relpath = pagePath(props.location).substring(1);
  const githubHref = props.noEdit
    ? undefined
    : `https://github.com/line/armeria/edit/master/site/src/pages/${relpath}${
        relpath === props.prefix ? '/index' : ''
      }.mdx`;
  let prevLabel;
  let nextLabel;
  let prevHref;
  let nextHref;
  if (props.pageContext.hrefs?.prev) {
    prevLabel = props.pageContext.hrefs.prev.label;
    prevHref = props.pageContext.hrefs.prev.href;
  } else if (currentMdxNode?.prevNodeName) {
    prevLabel =
      nameToMdxNode[currentMdxNode.prevNodeName].tableOfContents.items[0].title;
    prevHref = nameToMdxNode[currentMdxNode.prevNodeName].href;
  }
  if (props.pageContext.hrefs?.next) {
    nextLabel = props.pageContext.hrefs.next.label;
    nextHref = props.pageContext.hrefs.next.href;
  } else if (currentMdxNode?.nextNodeName) {
    nextLabel =
      nameToMdxNode[currentMdxNode.nextNodeName].tableOfContents.items[0].title;
    nextHref = nameToMdxNode[currentMdxNode.nextNodeName].href;
  }

  function findCurrentMdxNode(): any {
    const path = pagePath(props.location);
    const prefix = `/${props.prefix}`;
    const prefixPos = path.indexOf(prefix);

    const fallbackPageName = 'index';
    let pageName: string | undefined;
    if (prefixPos < 0) {
      pageName = fallbackPageName;
    } else {
      const pathWithoutPrefix = path.substring(prefixPos + prefix.length);
      if (pathWithoutPrefix === '' || pathWithoutPrefix === '/') {
        pageName = fallbackPageName;
      } else {
        pageName = pathWithoutPrefix.substring(1);
        if (pageName.endsWith('/')) {
          pageName = pageName.substring(0, pageName.length - 1);
        }
      }
    }

    for (let i = 0; i < mdxNodes.length; i += 1) {
      const e = mdxNodes[i];
      if (pageName === e.parent.name) {
        return e;
      }
    }

    return undefined;
  }

  function getMenuName(mdxNode: any, tocItem: any): string {
    if (props.menuTitle && mdxNode.frontmatter !== undefined) {
      if (mdxNode.frontmatter.menuTitle !== null) {
        if (mdxNode.frontmatter.order !== null) {
          return `${mdxNode.frontmatter.order}. ${mdxNode.frontmatter.menuTitle}`;
        }
        return `${mdxNode.frontmatter.menuTitle}`;
      }
    }

    return tocItem.title;
  }

  const globalToc = (
    <ol>
      {Object.entries(groupToMdxNodes).map(([group, groupedMdxNodes]) => {
        function renderMdxNodes() {
          return groupedMdxNodes.flatMap((mdxNode) => {
            return mdxNode.tableOfContents.items.map(
              (tocItem: any, i: number) => {
                const href = `${mdxNode.href}${i !== 0 ? tocItem.url : ''}`;
                const menuName = getMenuName(mdxNode, tocItem);
                return (
                  <li
                    key={href}
                    className={`${styles.tocLeaf} ${
                      href === pagePath(props.location)
                        ? styles.tocLeafActive
                        : ''
                    }`}
                  >
                    {href.includes('://') ? (
                      <OutboundLink href={href} title={tocItem.title}>
                        {tocItem.title}
                      </OutboundLink>
                    ) : (
                      <Link to={href} title={menuName}>
                        {menuName}
                      </Link>
                    )}
                  </li>
                );
              },
            );
          });
        }

        if (group === 'root') {
          return renderMdxNodes();
        }

        return (
          <li key={`group-${group}`} className={styles.tocGroup}>
            <span className={styles.tocGroupLabel}>{group}</span>
            <ol>{renderMdxNodes()}</ol>
          </li>
        );
      })}
    </ol>
  );

  return (
    <MDXProvider components={mdxComponents}>
      <BaseLayout
        location={props.location}
        pageTitle={pageTitle}
        pageDescription={pageDescription}
        contentClassName={styles.outerWrapper}
        main={false}
        extraSidebarContent={globalToc}
      >
        <div className={styles.wrapper}>
          <div className={styles.globalTocWrapper}>
            <nav>{globalToc}</nav>
          </div>
          <div className={styles.content}>
            <Content className="ant-typography" role="main">
              {props.children}
              <div className={styles.footer}>
                {githubHref ? (
                  <div className={styles.editOnGitHub}>
                    <OutboundLink href={githubHref}>
                      <GithubOutlined /> Edit this page
                    </OutboundLink>
                  </div>
                ) : (
                  ''
                )}
                {props.showPrevNextButton !== false && prevHref ? (
                  <Link className={styles.prevButton} to={prevHref}>
                    <Button>
                      <LeftOutlined />
                      <span className={styles.buttonLabel}> {prevLabel}</span>
                    </Button>
                  </Link>
                ) : (
                  ''
                )}
                {props.showPrevNextButton !== false && nextHref ? (
                  <Link className={styles.nextButton} to={nextHref}>
                    <Button>
                      <span className={styles.buttonLabel}>{nextLabel} </span>
                      <RightOutlined />
                    </Button>
                  </Link>
                ) : (
                  ''
                )}
              </div>
            </Content>
          </div>
          <div className={styles.pageTocWrapper} role="directory">
            <StickyBox offsetTop={24} offsetBottom={24}>
              <nav>
                <div className={styles.pageToc} />
                <div className={styles.newsletter}>
                  <Paragraph>Like what we&apos;re doing?</Paragraph>
                  <Mailchimp />
                </div>
              </nav>
            </StickyBox>
          </div>
        </div>
      </BaseLayout>
    </MDXProvider>
  );
};

export default MdxLayout;
