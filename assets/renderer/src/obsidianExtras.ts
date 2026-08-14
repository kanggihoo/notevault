import type MarkdownIt from 'markdown-it'

/**
 * Obsidian 하이라이트 문법 ==텍스트== → <mark>.
 *
 * 볼트 실측 33개 노트가 사용한다. markdown-it 에 없는 문법이므로 직접 처리한다.
 * 인라인 코드(`...`)는 backticks 규칙이 먼저 소비하므로 코드 안의 == 는
 * 건드리지 않는다.
 */
export function obsidianMark(md: MarkdownIt): void {
  md.inline.ruler.before('emphasis', 'obsidian_mark', (state, silent) => {
    const src = state.src
    const start = state.pos
    if (!src.startsWith('==', start)) return false

    const close = src.indexOf('==', start + 2)
    // 빈 하이라이트(====)나 닫힘 없음은 통과시킨다.
    if (close < 0 || close === start + 2) return false

    if (!silent) {
      const token = state.push('obsidian_mark', '', 0)
      token.content = src.slice(start + 2, close)
    }
    state.pos = close + 2
    return true
  })

  md.renderer.rules.obsidian_mark = (tokens, idx) =>
    `<mark>${md.utils.escapeHtml(tokens[idx].content)}</mark>`
}

/**
 * 표를 가로 스크롤 컨테이너로 감싼다.
 *
 * 볼트의 1171개 노트가 표를 쓰고 폰 폭을 넘는 표가 많다. 본문 자체가
 * 가로로 스크롤되면 안 되므로(design.md) 표마다 독립 스크롤을 준다.
 */
export function tableWrap(md: MarkdownIt): void {
  md.renderer.rules.table_open = () => '<div class="nv-table-wrap"><table>'
  md.renderer.rules.table_close = () => '</table></div>'
}

/**
 * mermaid 코드블록을 <pre class="nv-mermaid"> 로 바꾼다.
 *
 * 실제 SVG 렌더는 DOM 삽입 후 mermaid.run() 이 수행한다. 409개 노트가 사용.
 */
export function mermaidFence(md: MarkdownIt): void {
  const fallback = md.renderer.rules.fence!
  md.renderer.rules.fence = (tokens, idx, options, env, self) => {
    const token = tokens[idx]
    if (token.info.trim().split(/\s+/)[0] === 'mermaid') {
      return `<pre class="nv-mermaid">${md.utils.escapeHtml(token.content)}</pre>`
    }
    return fallback(tokens, idx, options, env, self)
  }
}
