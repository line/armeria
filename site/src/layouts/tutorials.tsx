import { RouteComponentProps } from '@reach/router';
import { graphql, useStaticQuery } from 'gatsby';
import React from 'react';

import docsIndex from '../pages/tutorials/toc.json';
import MdxLayout from './mdx';

interface TutorialLayoutProps extends RouteComponentProps {
  pageContext: any;
  pageTitle: string;
}

const TutorialLayout: React.FC<TutorialLayoutProps> = (props) => {
  const {
    allMdx: { nodes: candidateMdxNodes },
  } = useStaticQuery(graphql`
    query {
      allMdx(
        filter: { fileAbsolutePath: { glob: "**/src/pages/tutorials/**" } }
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
          frontmatter {
            menuTitle
            order
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
      prefix="tutorials"
      menuTitle
      pageTitleSuffix="Armeria tutorial"
    />
  );
};

export default TutorialLayout;
