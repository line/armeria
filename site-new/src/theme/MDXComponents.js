import React from 'react';
// Import the original mapper
import MDXComponents from '@theme-original/MDXComponents';
import AspectRatio from '@site/src/components/aspect-ratio';
import ApiLink from '@site/src/components/api-link';

export default {
  // Re-use the default mapping
  ...MDXComponents,
  // Map the "<AspectRatio>" tag to our AspectRatio component
  // `AspectRatio` will receive all props that were passed to `<AspectRatio>` in MDX
  AspectRatio,
  ApiLink,
};
