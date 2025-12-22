import React, { useState, useEffect } from 'react';
import { Steps as AntdSteps, StepsProps } from 'antd';
import useGlobalData from '@docusaurus/useGlobalData';
import { useLocation } from '@docusaurus/router';

const { Step } = AntdSteps;

interface TutorialStepProps extends StepsProps {
  position?: 'start' | 'end';
  order?: number;
  stepTitle?: string;
}

const TutorialSteps: React.FC<TutorialStepProps> = (props) => {
  const globalData = useGlobalData();
  const tutorialMetadata = globalData['tutorial-plugin'].default;
  const location = useLocation();

  const [tutorialType, setTutorialType] = useState('');

  useEffect(() => {
    setTutorialType(
      location.pathname
        .substring(0, location.pathname.lastIndexOf('/'))
        .replace('/docs/tutorials/', ''),
    );
  }, [location]);

  const tutorialSteps = Object.entries(tutorialMetadata)
    .filter(([relativePath]) =>
      relativePath.startsWith(`docs/tutorials/${tutorialType}/`),
    )
    .map(([relativePath, { menuTitle, order }]) => {
      return (
        <Step
          key={relativePath}
          title={`Step ${order}`}
          description={menuTitle}
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
    >
      {tutorialSteps}
    </AntdSteps>
  );
};

export default TutorialSteps;
