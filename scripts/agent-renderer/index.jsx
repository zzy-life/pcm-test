import 'core-js/actual';
import React, { useLayoutEffect, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { Streamdown } from 'streamdown';
import rehypeRaw from 'rehype-raw';
import rehypeSanitize from 'rehype-sanitize';
import './style.css';

// 只保留排版标签；不允许 URL、事件、内联样式或嵌入资源。
const schema = {
  tagNames: ['p', 'div', 'span', 'br', 'hr', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
    'strong', 'b', 'em', 'i', 'del', 's', 'blockquote', 'pre', 'code',
    'ul', 'ol', 'li', 'table', 'thead', 'tbody', 'tfoot', 'tr', 'th', 'td',
    'caption', 'sup', 'sub'],
  attributes: { td: ['colSpan', 'rowSpan'], th: ['colSpan', 'rowSpan'], ol: ['start'] },
  strip: ['script', 'style', 'iframe', 'object', 'embed', 'svg', 'math', 'form', 'input', 'img'],
  protocols: {}
};
const plugins = [rehypeRaw, [rehypeSanitize, schema]];
// 覆盖交互表格组件，避免工具栏和额外样式影响 HTML 合并单元格。
const components = Object.fromEntries(['table', 'thead', 'tbody', 'tfoot', 'tr', 'th', 'td'].map(tag =>
  [tag, ({ node, children, ...props }) => React.createElement(tag, props, children)]));
for (const tag of ['strong', 'em', 'del', 'pre', 'code', 'blockquote', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6']) {
  components[tag] = ({ children }) => React.createElement(tag, null, children);
}
components.table = ({ children }) => <div className="table-scroll"><table>{children}</table></div>;

let current = { text: '', running: false, revision: 0, following: true, y: 0 };
let apply;
let committed = -1;
let failed = false;
let following = true;
let programmatic = false;
let readingY = 0;
function atBottom() {
  return document.documentElement.scrollHeight - window.innerHeight - window.scrollY < 48;
}
window.addEventListener('scroll', () => {
  if (!programmatic) { following = atBottom(); readingY = window.scrollY; }
});
function bottom() {
  following = true;
  programmatic = true;
  window.scrollTo(0, document.documentElement.scrollHeight);
  requestAnimationFrame(() => { programmatic = false; });
}
class Boundary extends React.Component {
  state = { error: false };
  static getDerivedStateFromError() { return { error: true }; }
  componentDidCatch() { failed = true; }
  render() { return this.state.error ? <pre>{this.props.text}</pre> : this.props.children; }
}
function App() {
  const [value, setValue] = useState(current);
  apply = setValue;
  useLayoutEffect(() => {
    committed = value.revision;
    programmatic = true;
    window.scrollTo(0, following ? document.documentElement.scrollHeight : readingY);
    requestAnimationFrame(() => { programmatic = false; });
  }, [value]);
  return <Boundary key={value.session} text={value.text}>
    <Streamdown rehypePlugins={plugins} components={components} controls={false}
      isAnimating={value.running}>{value.text}</Streamdown>
  </Boundary>;
}
// 原生只向固定入口传 JSON，不暴露原生对象给模型生成的内容。
window.agentRenderer = {
  update(value) {
    if (value.reset) {
      following = value.following;
      readingY = value.y || 0;
      failed = false;
    }
    current = { ...value, text: value.snapshot ? value.text : current.text + value.text };
    apply(current);
  },
  bottom,
  state() { return { ready: Boolean(apply), committed, failed, following, y: window.scrollY }; }
};
createRoot(document.getElementById('root')).render(<App />);
