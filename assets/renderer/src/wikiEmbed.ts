import type MarkdownIt from 'markdown-it'

const IMAGE_EXT = /\.(png|jpe?g|gif|svg|webp|avif)$/i

/**
 * Obsidian 위키 문법을 처리하는 markdown-it 플러그인.
 *
 *   ![[attachments/Pasted image 1.png|718]]   → <img width:718px>
 *   ![[attachments/x.png|640x480]]            → <img width×height>
 *   [[Note Name]]                             → <a data-wikilink>
 *   [[Note Name|보이는 텍스트]]                 → <a data-wikilink>
 *
 * 기성 markdown-it-obsidian 을 쓰지 않는 이유는 매 매치마다
 * fs.readdirSync(process.cwd()) 로 볼트를 순회하는 정적 사이트 생성기용이라
 * WebView 에서 동작하지 않기 때문이다. 설계 문서 2.3절 참조.
 *
 * `[[` 에서만 발동하므로 표준 마크다운 이미지(543개)와 원격 URL(1436개)은
 * markdown-it 기본 동작으로 통과한다.
 */
export function wikiEmbed(md: MarkdownIt): void {
  md.inline.ruler.before('link', 'wiki_embed', (state, silent) => {
    const src = state.src
    const start = state.pos

    const isEmbed = src.startsWith('![[', start)
    if (!isEmbed && !src.startsWith('[[', start)) return false

    const open = start + (isEmbed ? 3 : 2)
    const close = src.indexOf(']]', open)
    if (close < 0) return false

    if (!silent) {
      const inner = src.slice(open, close)
      const pipe = inner.indexOf('|')
      const target = (pipe < 0 ? inner : inner.slice(0, pipe)).trim()
      const suffix = pipe < 0 ? undefined : inner.slice(pipe + 1).trim()

      // 파일명을 얻으려면 | 를 분리하는 것이 어차피 필수다.
      // 분리하지 않으면 "파일명.png|718" 이라는 이름을 찾게 된다.
      const type = isEmbed && IMAGE_EXT.test(target) ? 'wiki_img' : 'wiki_link'
      const token = state.push(type, '', 0)
      token.meta = { target, suffix }
    }

    state.pos = close + 2
    return true
  })

  md.renderer.rules.wiki_img = (tokens, idx) => {
    const { target, suffix } = tokens[idx].meta as { target: string; suffix?: string }
    const [w, h] = (suffix ?? '').split('x')

    const style = [
      w && Number(w) > 0 ? `width:${Number(w)}px` : '',
      h && Number(h) > 0 ? `height:${Number(h)}px` : '',
      // 화면 초과를 막는다. 크기 지정이 있어도 폰 폭을 넘지 않는다.
      'max-width:100%',
    ]
      .filter(Boolean)
      .join(';')

    // 파일명에 # & + , ( 같은 문자와 한글이 들어있다(볼트 실측 79개).
    // 경로 구분자는 유지해야 하므로 세그먼트별로 인코딩한다.
    const src = target.split('/').map(encodeURIComponent).join('/')
    const alt = md.utils.escapeHtml(target)

    return `<img class="nv-img" src="${src}" alt="${alt}" style="${style}" loading="lazy">`
  }

  md.renderer.rules.wiki_link = (tokens, idx) => {
    const { target, suffix } = tokens[idx].meta as { target: string; suffix?: string }
    const e = md.utils.escapeHtml
    // 렌더 시점에 링크 대상의 존재 여부를 조회하지 않는다.
    // 긴 노트에서 불필요한 비용이 발생한다. 설계 문서 8장 참조.
    return `<a class="nv-wikilink" data-wikilink="${e(target)}">${e(suffix || target)}</a>`
  }
}

/**
 * YAML frontmatter 를 제거한다.
 *
 * 볼트 실측 2944개 중 1365개(46%)에 frontmatter 가 있고, markdown-it 기본
 * 동작은 이를 수평선 + 키:값 텍스트로 렌더한다. 읽기에 집중하도록 숨긴다.
 */
export function stripFrontmatter(source: string): string {
  if (!source.startsWith('---')) return source
  const match = /^---\r?\n([\s\S]*?)\r?\n---[ \t]*(\r?\n|$)/.exec(source)
  return match ? source.slice(match[0].length) : source
}

/**
 * 볼트에만 있는 비표준 콜아웃 타입을 코어 타입으로 정규화한다.
 *
 * `warn`(35개)은 Obsidian 코어 매핑에 없어 기본값(파랑)으로 떨어지지만
 * 의미상 주황이 맞으므로 warning 으로 별칭 처리한다. design.md 3장 참조.
 */
export function normalizeCallouts(source: string): string {
  return source.replace(/^(>\s*)\[!warn\]/gim, '$1[!warning]')
}
