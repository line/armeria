import React from 'react';
import { Steps as AntdSteps, StepsProps} from 'antd';
import styles from './steps.module.less';
import {graphql, useStaticQuery} from "gatsby";
import {RouteComponentProps} from "@reach/router";

const { Step } = AntdSteps;

interface TutorialStepProps extends StepsProps{
    position?: 'start'|'end';
    order?: number;
    stepTitle?: string;
}

const TutorialSteps:React.FC<TutorialStepProps> = (props) => {
    function getTutorialSteps() {
        const {
            allMdx: {nodes: tutorialNodes},
        } = useStaticQuery(graphql`
        query{
            allMdx(filter: {fileAbsolutePath: {glob: "**/src/pages/tutorials/**"},
                frontmatter: {type: {eq: "step"}}}
                sort: {fields:[frontmatter___order], order:ASC}){
                nodes {
                    frontmatter {
                        menuTitle
                        order
                    }
                }
            }
        }`
        );
        return (
            Object.entries(tutorialNodes).map(([key, tutorialNode]) => {
                return (
                    <Step
                        key={key}
                        title={`Step ${tutorialNode.frontmatter.order}`}
                        description={tutorialNode.frontmatter.menuTitle}
                    ></Step>
                );
            })
        );
    }
    return (
        <AntdSteps
            {...props}
            type='default'
            direction='horizontal'
            responsive='true'
            size='small'
            progressDot
            initial={1}
            className={styles.tutorialTheme}
        >
            {getTutorialSteps()}
        </AntdSteps>
    );
};

export default TutorialSteps;