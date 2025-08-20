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

const Article: React.FC<ArticleProps> = (article) => {
  const typeIcons = Array.isArray(article.type)
    ? article.type.map((type) => type2icon[type])
    : type2icon[article.type || 'article'];
  const typesLabel = getTypeLabel(article.type);
  const isExternalUrl =
    article.url.startsWith('http://') || article.url.startsWith('https://');

  return (
    <div className={`${styles.articleCardItem} col col--12`}>
      <Link
        href={article.url}
        className="padding-top--lg padding-left--lg padding-right--lg"
      >
        <Heading as="h3">
          {isExternalUrl && (
            <span className={styles.externalIcon}>
              <IconExternalLink />
            </span>
          )}
          {typeIcons} {article.title}
        </Heading>
        <p>
          <span>
            {typesLabel} by {article.author},{' '}
          </span>{' '}
          <span>{dayjs(article.date).fromNow()}</span>
        </p>
        {article.description && <p>{article.description}</p>}
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
