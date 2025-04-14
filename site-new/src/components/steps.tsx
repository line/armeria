import React, { useState, useEffect } from 'react';
import { Steps as AntdSteps, StepsProps } from 'antd';
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';

const { Step } = AntdSteps;

interface TutorialStepProps extends StepsProps {
  position?: 'start' | 'end';
  order?: number;
  stepTitle?: string;
}

const TutorialSteps: React.FC<TutorialStepProps> = (props) => {
    return (
        <span>tutorial steps</span>
    );
};

export default TutorialSteps;