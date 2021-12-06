import React from 'react';
import { Steps as AntdSteps, StepsProps } from 'antd';
import { graphql, useStaticQuery } from 'gatsby';
import styles from './steps.module.less';

const { Step } = AntdSteps;

interface TutorialStepProps extends StepsProps {
  position?: 'start' | 'end';
  order?: number;
  stepTitle?: string;
}

const TutorialSteps: React.FC<TutorialStepProps> = (props) => {
  const {
    allMdx: { nodes: tutorialNodes },
  } = useStaticQuery(graphql`
    query {
      allMdx(
        filter: {
          fileAbsolutePath: { glob: "**/src/pages/tutorials/**" }
          frontmatter: { type: { eq: "step" } }
        }
        sort: { fields: [frontmatter___order], order: ASC }
      ) {
        nodes {
          frontmatter {
            menuTitle
            order
          }
          parent {
            ... on File {
              relativeDirectory
            }
          }
        }
      }
    }
  `);

  const tutorialType = window.location.pathname
    .substr(0, window.location.pathname.lastIndexOf('/'))
    .replace('/tutorials/', '');

  const tutorialSteps = Object.entries(
    tutorialNodes.filter(
      (tutorialNode) => tutorialNode.parent.relativeDirectory === tutorialType,
    ),
  ).map(([key, tutorialNode]) => {
    return (
      <Step
        key={key}
        title={`Step ${tutorialNode.frontmatter.order}`}
        description={tutorialNode.frontmatter.menuTitle}
      />
    );
  });

  return (
    <AntdSteps
      {...props}
      type="default"
      direction="horizontal"
      responsive
      size="small"
      progressDot
      initial={1}
      className={styles.tutorialTheme}
    >
      {tutorialSteps}
    </AntdSteps>
  );
};

export default TutorialSteps;
