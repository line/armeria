import {
  CloseOutlined,
  GithubOutlined,
  LeftOutlined,
  RightOutlined,
  UnorderedListOutlined,
} from '@ant-design/icons';
import loadable from '@loadable/component';
import { MDXProvider } from '@mdx-js/react';
import { globalHistory, WindowLocation } from '@reach/router';
import { Button, Layout, Select, Tabs as AntdTabs } from 'antd';
import { Link, navigate, withPrefix } from 'gatsby';
import { OutboundLink } from 'gatsby-plugin-google-analytics';
import React from 'react';
import StickyBox from 'react-sticky-box';
import tocbot from 'tocbot';

import 'antd/es/table/style';

import { Tip, Warning } from '../components/alert';
import { TypeLink } from '../components/api-link';
import AspectRatio from '../components/aspect-ratio';
import CodeBlock from '../components/code-block';
import Emoji from '../components/emoji';
import MaxWidth from '../components/max-width';
import NoWrap from '../components/nowrap';
import BaseLayout from './base';
import pagePath from './page-path';
import styles from './mdx.module.less';

const { Content } = Layout;

interface MdxLayoutProps {
  location: WindowLocation;
  pageContext: any;
  candidateMdxNodes: any[];
  index: { [section: string]: string[] | { [title: string]: string } };
  prefix: string;
  pageTitle: string;
  pageTitleSuffix: string;
  showPrevNextButton?: boolean;
  noEdit?: boolean;
}

enum ToCState {
  CLOSED,
  OPENING,
  OPEN,
  CLOSING,
}

const tocAnimationDurationMillis = 300;
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
      <CodeBlock language={language}>{props.children.props.children}</CodeBlock>
    );
  },
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
  Mailchimp: loadable(() => import('../components/mailchimp')),
  MaxWidth,
  NoWrap,
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

const MdxLayout: React.FC<MdxLayoutProps> = props => {
  React.useLayoutEffect(() => {
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
      mdxNode.href = `/${props.prefix}${
        mdxNode.parent.name === 'index' ? '' : `/${mdxNode.parent.name}`
      }`;
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
  const showSearch = typeof window === 'undefined' || window.innerWidth > 768;
  const pageTitle = `${props.pageTitle} — ${props.pageTitleSuffix}`;
  const relpath = pagePath(props.location).substring(1);
  const githubHref = props.noEdit
    ? undefined
    : `https://github.com/line/armeria/tree/master/site/src/pages/${relpath}${
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

  // States required for opening and closing ToC
  const [tocState, setTocState] = React.useState(ToCState.CLOSED);
  const tocStateRef = React.useRef(tocState);
  tocStateRef.current = tocState;

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

  function toggleToC() {
    switch (tocState) {
      case ToCState.CLOSED:
        setTocState(ToCState.OPENING);
        setTimeout(() => {
          if (tocStateRef.current === ToCState.OPENING) {
            setTocState(ToCState.OPEN);
          }
        });
        break;
      case ToCState.OPEN:
        setTocState(ToCState.CLOSING);
        setTimeout(() => {
          if (tocStateRef.current === ToCState.CLOSING) {
            setTocState(ToCState.CLOSED);
          }
        }, tocAnimationDurationMillis);
        break;
      default:
      // Animation in progress. Let the user wait a little bit.
    }
  }

  // Style functions for fading in/out table of contents.
  function pageTocWrapperStyle(): React.CSSProperties {
    switch (tocState) {
      case ToCState.OPENING:
        return {
          display: 'block',
          opacity: 0,
          zIndex: 8,
        };
      case ToCState.OPEN:
        return {
          display: 'block',
          opacity: 1,
          zIndex: 8,
        };
      case ToCState.CLOSING:
        return {
          display: 'block',
          opacity: 0,
          zIndex: 8,
        };
      default:
        return { zIndex: 'auto' };
    }
  }

  return (
    <MDXProvider components={mdxComponents}>
      <BaseLayout
        location={props.location}
        pageTitle={pageTitle}
        contentClassName={styles.outerWrapper}
        main={false}
      >
        <div className={styles.wrapper}>
          <div className={styles.globalTocWrapper}>
            <nav>
              <ol>
                {Object.entries(groupToMdxNodes).map(
                  ([group, groupedMdxNodes]) => {
                    function renderMdxNodes() {
                      return groupedMdxNodes.flatMap(mdxNode => {
                        return mdxNode.tableOfContents.items.map(
                          (tocItem: any, i: number) => {
                            const href = `${mdxNode.href}${
                              i !== 0 ? tocItem.url : ''
                            }`;

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
                                  <OutboundLink
                                    href={href}
                                    title={tocItem.title}
                                  >
                                    {tocItem.title}
                                  </OutboundLink>
                                ) : (
                                  <Link to={href} title={tocItem.title}>
                                    {tocItem.title}
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
                  },
                )}
              </ol>
            </nav>
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
          <div className={styles.tocButton}>
            <StickyBox offsetTop={24} offsetBottom={24}>
              <Button onClick={toggleToC}>
                {tocState === ToCState.OPEN ? (
                  <CloseOutlined title="Close table of contents" />
                ) : (
                  <UnorderedListOutlined title="Open table of contents" />
                )}
              </Button>
            </StickyBox>
          </div>
          {/* eslint-disable jsx-a11y/no-noninteractive-element-interactions */}
          {/* eslint-disable jsx-a11y/click-events-have-key-events */}
          <div
            className={styles.pageTocWrapper}
            style={pageTocWrapperStyle()}
            role="directory"
            onClick={(e: any) => {
              if (e.target.className === styles.pageTocWrapper) {
                toggleToC();
              }
            }}
          >
            {/* eslint-enable jsx-a11y/click-events-have-key-events */}
            {/* eslint-enable jsx-a11y/no-noninteractive-element-interactions */}
            <StickyBox
              offsetTop={24}
              offsetBottom={24}
              className={styles.pageTocShadow}
            >
              <nav>
                <div className={styles.pageToc} />
                <Select
                  showSearch={showSearch}
                  placeholder="Jump to other page"
                  onChange={href => {
                    const hrefStr = `${href}`;
                    if (hrefStr.includes('://')) {
                      globalHistory.navigate(hrefStr);
                    } else {
                      navigate(hrefStr);
                    }
                  }}
                  filterOption={(input, option) =>
                    option.children
                      ?.toLowerCase()
                      .indexOf(input.toLowerCase()) >= 0
                  }
                >
                  {Object.entries(groupToMdxNodes).map(
                    ([group, groupedMdxNodes]) => {
                      function renderMdxNodes() {
                        return groupedMdxNodes.map(mdxNode => (
                          <Select.Option
                            key={mdxNode.href}
                            value={mdxNode.href}
                          >
                            {mdxNode.tableOfContents.items[0].title}
                          </Select.Option>
                        ));
                      }

                      if (group === 'root') {
                        return renderMdxNodes();
                      }

                      return (
                        <Select.OptGroup
                          key={`group-${group}`}
                          label={group.toUpperCase()}
                        >
                          {renderMdxNodes()}
                        </Select.OptGroup>
                      );
                    },
                  )}
                </Select>
              </nav>
            </StickyBox>
          </div>
        </div>
      </BaseLayout>
    </MDXProvider>
  );
};

export default MdxLayout;
