import { WindowLocation } from '@reach/router';
import { withPrefix } from 'gatsby';

const prefix = withPrefix('/');

export default (location: WindowLocation) => {
  let path = location.pathname;
  if (path.startsWith(prefix)) {
    path = path.substring(prefix.length - 1);
  }

  if (path.length > 1 && path.endsWith('/')) {
    path = path.substring(0, path.length - 1);
  } else if (path.endsWith('/index')) {
    path = path.substring(0, path.length - 6);
  } else if (path.endsWith('/index.html')) {
    path = path.substring(0, path.length - 11);
  }

  return path;
};
