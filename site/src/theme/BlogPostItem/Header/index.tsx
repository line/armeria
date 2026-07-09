import React, { type ReactNode } from 'react';
import BlogPostItemHeaderTitle from '@theme/BlogPostItem/Header/Title';
import BlogPostItemHeaderInfo from '@theme/BlogPostItem/Header/Info';
import BlogPostItemHeaderAuthors from '@theme/BlogPostItem/Header/Authors';

import { useBlogPost } from '@docusaurus/plugin-content-blog/client';
import Admonition from '@theme/Admonition';
import Link from '@docusaurus/Link';

interface I18nLinkData {
  ko?: string;
  ja?: string;
  en?: string;
}

const I18nLink: React.FC<I18nLinkData> = ({ ko, ja, en }) => {
  const links = [
    ko && (
      <Link to={ko} key="ko">
        Korean
      </Link>
    ),
    ja && (
      <Link to={ja} key="ja">
        Japanese
      </Link>
    ),
    en && (
      <Link to={en} key="en">
        English
      </Link>
    ),
  ].filter(Boolean);

  if (links.length === 0) return null;

  return (
    <div>
      <Admonition type="info">
        <p>
          This post is also available in{' '}
          {links.length === 1 ? (
            links[0]
          ) : (
            <>
              {links[0]} and {links[1]}
            </>
          )}
          .
        </p>
      </Admonition>
    </div>
  );
};

const BlogPostItemHeader = (): ReactNode => {
  const { metadata, isBlogPostPage } = useBlogPost();
  const { frontMatter } = metadata;
  const { other_languages: i18nLinkData } = frontMatter as {
    other_languages?: I18nLinkData;
  };

  return (
    <header>
      {isBlogPostPage && i18nLinkData && <I18nLink {...i18nLinkData} />}
      <BlogPostItemHeaderTitle />
      <BlogPostItemHeaderInfo />
      <BlogPostItemHeaderAuthors />
    </header>
  );
};

export default BlogPostItemHeader;
