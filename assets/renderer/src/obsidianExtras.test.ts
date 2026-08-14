import MarkdownIt from 'markdown-it'
import { mermaidFence, obsidianMark, tableWrap } from './obsidianExtras'

const md = new MarkdownIt().use(obsidianMark).use(tableWrap).use(mermaidFence)

describe('obsidianMark (==하이라이트==)', () => {
  it('문장 중간의 하이라이트를 <mark> 로 렌더한다', () => {
    expect(md.renderInline('앞 ==중간== 뒤')).toBe('앞 <mark>중간</mark> 뒤')
  })

  it('한 문장의 여러 하이라이트를 각각 처리한다', () => {
    const out = md.renderInline('==A==와 ==B==')
    expect(out).toBe('<mark>A</mark>와 <mark>B</mark>')
  })

  it('코드 안의 == 는 건드리지 않는다', () => {
    const out = md.render('```js\nif (a === "x") {}\n```')
    expect(out).not.toContain('<mark>')
  })

  it('인라인 코드 안의 == 도 건드리지 않는다', () => {
    expect(md.renderInline('`a === b` 비교')).not.toContain('<mark>')
  })

  it('닫히지 않으면 평문으로 남는다', () => {
    expect(md.renderInline('== 열림만')).not.toContain('<mark>')
  })
})

describe('tableWrap', () => {
  it('표를 가로 스크롤 컨테이너로 감싼다', () => {
    const out = md.render('| a | b |\n|---|---|\n| 1 | 2 |')
    expect(out).toContain('<div class="nv-table-wrap"><table>')
    expect(out).toContain('</table></div>')
  })
})

describe('mermaidFence', () => {
  it('mermaid 펜스를 nv-mermaid pre 로 바꾼다', () => {
    const out = md.render('```mermaid\ngraph TD\nA-->B\n```')
    expect(out).toContain('<pre class="nv-mermaid">')
    expect(out).toContain('A--&gt;B')
  })

  it('다른 언어 펜스는 기본 처리를 유지한다', () => {
    const out = md.render('```js\nconst a = 1\n```')
    expect(out).toContain('language-js')
    expect(out).not.toContain('nv-mermaid')
  })

  it('4중 백틱 안의 mermaid 예제는 코드로 남는다', () => {
    const out = md.render('````\n```mermaid\ngraph TD\n```\n````')
    expect(out).not.toContain('nv-mermaid')
  })
})
