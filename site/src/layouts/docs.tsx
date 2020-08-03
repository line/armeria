import { RouteComponentProps } from '@reach/router';
import { graphql, useStaticQuery } from 'gatsby';
import React from 'react';

import docsIndex from '../pages/docs/toc.json';
import MdxLayout from './mdx';

interface DocsLayoutProps extends RouteComponentProps {
  pageContext: any;
  pageTitle: string;
}

const DocsLayout: React.FC<DocsLayoutProps> = (props) => {
  const {
    allMdx: { nodes: candidateMdxNodes },
  } = useStaticQuery(graphql`
    query {
      allMdx(filter: { fileAbsolutePath: { glob: "**/src/pages/docs/**" } }) {
        nodes {
          tableOfContents(maxDepth: 1)
          excerpt(pruneLength: 256, truncate: true)
          parent {
            ... on File {
              sourceInstanceName
              name
            }
          }
        }
      }
    }
  `);

  return (
    <MdxLayout
      {...props}
      candidateMdxNodes={candidateMdxNodes}
      index={docsIndex}
      prefix="docs"
      pageTitleSuffix="Armeria documentation"
    />
  );
};

export default DocsLayout;
