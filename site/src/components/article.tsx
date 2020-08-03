import {
  FileOutlined,
  FundProjectionScreenOutlined,
  PlaySquareOutlined,
} from '@ant-design/icons';
import { Typography } from 'antd';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import { OutboundLink } from 'gatsby-plugin-google-analytics';
import React from 'react';

import styles from './article.module.less';

const { Title, Paragraph } = Typography;

dayjs.extend(relativeTime);

interface ArticleProps {
  title: string;
  url: string;
  author: string;
  date: string;
  type?: 'document' | 'slides' | 'video';
  description?: string;
}

const type2icon = {
  document: <FileOutlined alt="[Article]" aria-label="[Article]" />,
  slides: <FundProjectionScreenOutlined alt="[Slides]" aria-label="[Slides]" />,
  video: <PlaySquareOutlined alt="[Video]" aria-label="[Video]" />,
};

const Article: React.FC<ArticleProps> = (props) => {
  return (
    <>
      <Title level={4} id={props.url} className={styles.title}>
        <OutboundLink href={props.url}>{props.title}</OutboundLink>{' '}
        <span className={styles.author}>
          {(props.type || 'document') !== 'document'
            ? `${props.type.charAt(0).toUpperCase()}${props.type.substring(1)} `
            : null}
          by {props.author},{' '}
          <span title={dayjs(props.date).format('YYYY-MM-DD')}>
            {dayjs(props.date).fromNow()}
          </span>
        </span>
      </Title>
      <Paragraph className={styles.link}>
        <OutboundLink href={props.url}>
          {type2icon[props.type || 'document']}{' '}
          {props.url.replace(/^[^:]+:\/\//, '')}
        </OutboundLink>
      </Paragraph>
      {props.description ? <Paragraph>{props.description}</Paragraph> : null}
    </>
  );
};

export default Article;
