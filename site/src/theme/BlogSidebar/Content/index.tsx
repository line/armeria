/* eslint-disable import/no-extraneous-dependencies, react/function-component-definition, no-else-return */
import React, { memo, type ReactNode } from 'react';
import { useThemeConfig } from '@docusaurus/theme-common';
import { groupBlogSidebarItemsByYear } from '@docusaurus/plugin-content-blog/client';
import Heading from '@theme/Heading';
import type { Props } from '@theme/BlogSidebar/Content';

/* Swizzling starts */
import BlogLanguageSelector from '@site/src/components/blog-language-selector';
/* Swizzling ends */

function BlogSidebarYearGroup({
  year,
  yearGroupHeadingClassName,
  children,
}: {
  year: string;
  yearGroupHeadingClassName?: string;
  children: ReactNode;
}) {
  return (
    <div role="group">
      <Heading as="h3" className={yearGroupHeadingClassName}>
        {year}
      </Heading>
      {children}
    </div>
  );
}

function BlogSidebarContent({
  items,
  yearGroupHeadingClassName,
  ListComponent,
}: Props): ReactNode {
  const themeConfig = useThemeConfig();
  /* Swizzling starts */
  const isArmeriaBlog = items && items[0].permalink.includes('blog');
  /* Swizzling ends */
  if (themeConfig.blog.sidebar.groupByYear) {
    const itemsByYear = groupBlogSidebarItemsByYear(items);
    return (
      <>
        {/* Swizzling starts */}
        {isArmeriaBlog && <BlogLanguageSelector />}
        {/* Swizzling ends */}
        {itemsByYear.map(([year, yearItems]) => (
          <BlogSidebarYearGroup
            key={year}
            year={year}
            yearGroupHeadingClassName={yearGroupHeadingClassName}
          >
            <ListComponent items={yearItems} />
          </BlogSidebarYearGroup>
        ))}
      </>
    );
  } else {
    /* Swizzling starts */
    return (
      <>
        {isArmeriaBlog && <BlogLanguageSelector />}
        <ListComponent items={items} />
      </>
    );
    /* Swizzling ends */
  }
}

export default memo(BlogSidebarContent);
/* eslint-enable import/no-extraneous-dependencies, react/function-component-definition, no-else-return */
