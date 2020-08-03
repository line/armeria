import { RouteComponentProps } from '@reach/router';
import { graphql, useStaticQuery } from 'gatsby';
import React from 'react';

import communityIndex from '../pages/community/toc.json';
import MdxLayout from './mdx';

interface CommunityLayoutProps extends RouteComponentProps {
  pageContext: any;
  pageTitle: string;
}

const CommunityLayout: React.FC<CommunityLayoutProps> = (props) => {
  const {
    allMdx: { nodes: candidateMdxNodes },
  } = useStaticQuery(graphql`
    query {
      allMdx(
        filter: { fileAbsolutePath: { glob: "**/src/pages/community/**" } }
      ) {
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
      index={communityIndex}
      prefix="community"
      pageTitleSuffix="Armeria community"
    />
  );
};

export default CommunityLayout;
