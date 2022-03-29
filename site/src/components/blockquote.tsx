import { Typography } from 'antd';
import React from 'react';

import { useStaticQuery, graphql } from 'gatsby';
import * as styles from './blockquote.module.less';

const { Title, Paragraph } = Typography;

interface BlockquoteProps {
  author: React.ReactNode;
  from: React.ReactNode;
  bgColor1: string;
  bgColor2: string;
  reverse?: boolean;
}

const Blockquote: React.FC<BlockquoteProps> = (props) => {
  const data = useStaticQuery(graphql`
    query {
      pattern1: file(relativePath: { eq: "pattern-1.svg" }) {
        publicURL
      }

      pattern2: file(relativePath: { eq: "pattern-2.svg" }) {
        publicURL
      }
    }
  `);

  return (
    <blockquote className={styles.blockquote}>
      <img
        className={
          props.reverse
            ? styles.backgroundPattern1Reverse
            : styles.backgroundPattern1
        }
        src={props.reverse ? data.pattern2.publicURL : data.pattern1.publicURL}
        height={200}
        alt=" "
        aria-hidden
      />
      <img
        className={
          props.reverse
            ? styles.backgroundPattern2Reverse
            : styles.backgroundPattern2
        }
        src={props.reverse ? data.pattern1.publicURL : data.pattern2.publicURL}
        height={200}
        alt=" "
        aria-hidden
      />
      <Paragraph
        className={styles.body}
        style={{ backgroundColor: props.bgColor1 }}
      >
        {props.children}
        <span
          className={styles.quote}
          style={{ borderColor: props.bgColor2 }}
        />
      </Paragraph>
      <Title level={4} className={styles.author}>
        <span className={styles.separator}>&mdash; </span>
        {props.author}
        <br />
        <span className={styles.from}>{props.from}</span>
      </Title>
    </blockquote>
  );
};

export default Blockquote;
