// 타입 선언이 없는 렌더러 의존성.

declare module 'markdown-it-obsidian-callouts' {
  import type MarkdownIt from 'markdown-it'
  const plugin: (md: MarkdownIt) => void
  export default plugin
}

declare module 'katex/contrib/auto-render' {
  type Delimiter = { left: string; right: string; display: boolean }
  export default function renderMathInElement(
    element: HTMLElement,
    options?: { delimiters?: Delimiter[]; throwOnError?: boolean },
  ): void
}
