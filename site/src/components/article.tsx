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

import * as styles from './article.module.less';

const { Title, Paragraph } = Typography;

dayjs.extend(relativeTime);

type ArticleType = 'article' | 'slides' | 'video';

interface ArticleProps {
  title: string;
  url: string;
  author: string;
  date: string;
  type?: ArticleType | ArticleType[];
  description?: string;
}

const type2icon = {
  article: <FileOutlined alt="[Article]" aria-label="[Article]" />,
  slides: <FundProjectionScreenOutlined alt="[Slides]" aria-label="[Slides]" />,
  video: <PlaySquareOutlined alt="[Video]" aria-label="[Video]" />,
};

const Article: React.FC<ArticleProps> = (props) => {
  const typeIcons = [];
  let typesLabel = '';
  if (typeof props.type === 'string') {
    typesLabel = capitalize(props.type);
    typeIcons.push(type2icon[props.type]);
  } else if (Array.isArray(props.type)) {
    if (props.type.length === 1) {
      typesLabel = capitalize(props.type[0]);
    } else {
      typesLabel = props.type
        .map((value, index) => {
          if (index === 0) {
            return capitalize(value);
          }
          if (index !== props.type.length - 1) {
            return `, ${value}`;
          }
          return ` and ${value}`;
        })
        .join('');
    }

    props.type.forEach((value) => typeIcons.push(type2icon[value]));
  }

  if (typesLabel === 'Article') {
    typesLabel = '';
  }

  return (
    <>
      <Title level={4} id={props.url} className={styles.title}>
        <OutboundLink href={props.url}>{props.title}</OutboundLink>{' '}
        <span className={styles.author}>
          {typesLabel} by {props.author},{' '}
          <span title={dayjs(props.date).format('YYYY-MM-DD')}>
            {dayjs(props.date).fromNow()}
          </span>
        </span>
      </Title>
      <Paragraph className={styles.link}>
        <OutboundLink href={props.url}>
          {typeIcons} {props.url.replace(/^[^:]+:\/\//, '')}
        </OutboundLink>
      </Paragraph>
      {props.description ? (
        <Paragraph>
          {/* eslint-disable react/no-danger */}
          <span dangerouslySetInnerHTML={{ __html: props.description }} />
          {/* eslint-enable react/no-danger */}
        </Paragraph>
      ) : null}
    </>
  );
};

function capitalize(value: string) {
  return `${value.charAt(0).toUpperCase()}${value.substring(1)}`;
}

export default Article;
