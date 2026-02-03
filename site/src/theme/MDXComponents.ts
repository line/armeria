// Import the original mapper
import MDXComponents from '@theme-original/MDXComponents';
import ApiLink from '@site/src/components/api-link';
import AspectRatio from '@site/src/components/aspect-ratio';
import MaxWidth from '@site/src/components/max-width';
import RequiredDependencies from '@site/src/components/required-dependencies';
import TutorialSteps from '@site/src/components/steps';
import ThankYou from '@site/src/components/thank-you';
import Mailchimp from '@site/src/components/mailchimp';
import AnimatedLogo from '@site/src/components/animated-logo';

import CodeBlock from '@theme/CodeBlock';
import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';
import DocCardList from '@theme/DocCardList';

export default {
  // Re-use the default mapping
  ...MDXComponents,
  // Map custom tags to our corresponding components
  ApiLink,
  AspectRatio,
  MaxWidth,
  RequiredDependencies,
  TutorialSteps,
  ThankYou,
  Mailchimp,
  AnimatedLogo,
  CodeBlock,
  Tabs,
  TabItem,
  DocCardList,
};
