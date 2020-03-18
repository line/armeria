import { Typography } from 'antd';
import React from 'react';

import { useStaticQuery, graphql } from 'gatsby';
import styles from './blockquote.module.less';

const { Title, Paragraph } = Typography;

interface BlockquoteProps {
  author: React.ReactNode;
  from: React.ReactNode;
  bgColor1: string;
  bgColor2: string;
}

const Blockquote: React.FC<BlockquoteProps> = props => {
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
        className={styles.backgroundPattern1}
        src={data.pattern1.publicURL}
        alt=" "
        aria-hidden
      />
      <img
        className={styles.backgroundPattern2}
        src={data.pattern2.publicURL}
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
