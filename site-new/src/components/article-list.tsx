import React from 'react';
import Heading from '@theme/Heading';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import Link from '@docusaurus/Link';
import IconExternalLink from '@theme/Icon/ExternalLink';

import styles from './article-list.module.css';

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

interface ArticleListProps {
  articles: ArticleProps[];
}

const type2icon = {
  article: '📄',
  slides: '📊',
  video: '🎥',
};

function capitalize(value: string) {
  return `${value.charAt(0).toUpperCase()}${value.substring(1)}`;
}

function getTypeLabel(type: ArticleType | ArticleType[] | undefined): string {
  let typesLabel = '';

  if (!type) {
    return '';
  }
  if (typeof type === 'string') {
    typesLabel = capitalize(type);
  } else if (Array.isArray(type)) {
    if (type.length === 1) {
      typesLabel = capitalize(type[0]);
    } else {
      typesLabel = type
        .map((value, index) => {
          if (index === 0) {
            return capitalize(value);
          }
          if (index !== type.length - 1) {
            return `, ${value}`;
          }
          return ` and ${value}`;
        })
        .join('');
    }
  }
  if (typesLabel === 'Article') {
    return '';
  }

  return typesLabel;
}

const Article: React.FC<ArticleProps> = ({
  title,
  url,
  author,
  date,
  type,
  description,
}) => {
  const typeIcons = Array.isArray(type)
    ? type.map((typeItem) => type2icon[typeItem])
    : type2icon[type || 'article'];
  const typesLabel = getTypeLabel(type);
  const isExternalUrl = url.startsWith('http://') || url.startsWith('https://');

  return (
    <div className={`${styles.articleCardItem} col col--12`}>
      <Link
        href={url}
        className="padding-top--lg padding-left--lg padding-right--lg"
      >
        <Heading as="h3">
          {isExternalUrl && (
            <span className={styles.externalIcon}>
              <IconExternalLink />
            </span>
          )}
          {typeIcons} {title}
        </Heading>
        <p>
          <span>
            {typesLabel} by {author},{' '}
          </span>{' '}
          <span>{dayjs(date).fromNow()}</span>
        </p>
        {description && <p>{description}</p>}
      </Link>
    </div>
  );
};

const ArticleList: React.FC<ArticleListProps> = ({ articles }) => (
  <div className="row">
    {articles.map((article) => (
      <Article key={article.title} {...article} />
    ))}
  </div>
);

export default ArticleList;
