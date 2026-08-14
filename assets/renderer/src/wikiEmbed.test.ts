import MarkdownIt from 'markdown-it'
import {
  normalizeCallouts,
  stripFrontmatter,
  wikiEmbed,
} from './wikiEmbed'

const md = new MarkdownIt().use(wikiEmbed)
const render = (src: string) => md.renderInline(src)

describe('위키 이미지 임베드', () => {
  it('크기 지정을 width 로 적용한다', () => {
    const out = render('![[attachments/Pasted image 20260320144034.png|718]]')
    expect(out).toContain('width:718px')
    expect(out).toContain('max-width:100%')
  })

  it('WxH 형식을 width·height 로 적용한다', () => {
    const out = render('![[attachments/x.png|640x480]]')
    expect(out).toContain('width:640px')
    expect(out).toContain('height:480px')
  })

  it('크기 지정이 없으면 max-width 만 적용한다', () => {
    const out = render('![[attachments/x.png]]')
    expect(out).toContain('max-width:100%')
    expect(out).not.toContain('width:0')
  })

  it('공백·특수문자·한글 파일명을 URL 인코딩한다', () => {
    const out = render('![[attachments/Pasted image 1.png]]')
    expect(out).toContain('attachments/Pasted%20image%201.png')
    // 경로 구분자는 인코딩하지 않는다
    expect(out).not.toContain('attachments%2F')
  })

  it('경로 구분자를 유지하면서 세그먼트를 인코딩한다', () => {
    const out = render('![[attachments/shared buffer/그림 1.png]]')
    expect(out).toContain('attachments/shared%20buffer/')
    expect(out).toContain(encodeURIComponent('그림 1.png'))
  })
})

describe('위키링크', () => {
  it('data-wikilink 로 렌더한다', () => {
    const out = render('[[Note Name]]')
    expect(out).toContain('data-wikilink="Note Name"')
    expect(out).toContain('>Note Name<')
  })

  it('별칭이 있으면 별칭을 표시하고 대상은 유지한다', () => {
    const out = render('[[Note Name|보이는 텍스트]]')
    expect(out).toContain('data-wikilink="Note Name"')
    expect(out).toContain('>보이는 텍스트<')
  })

  it('이미지가 아닌 임베드는 링크로 처리한다', () => {
    const out = render('![[Some Note]]')
    expect(out).toContain('data-wikilink="Some Note"')
  })
})

describe('표준 마크다운은 손대지 않는다', () => {
  it('상대경로 이미지가 그대로 통과한다 (543개)', () => {
    const out = render('![그림](<attachments/shared-buffer/fig-1-15.png>)')
    expect(out).toContain('src="attachments/shared-buffer/fig-1-15.png"')
    expect(out).not.toContain('data-wikilink')
  })

  it('원격 URL 이 그대로 통과한다 (1436개)', () => {
    const out = render('![원격](https://imagedelivery.net/x.png)')
    expect(out).toContain('src="https://imagedelivery.net/x.png"')
  })

  it('일반 링크가 그대로 통과한다', () => {
    const out = render('[텍스트](https://example.com)')
    expect(out).toContain('href="https://example.com"')
    expect(out).not.toContain('data-wikilink')
  })

  it('닫히지 않은 대괄호를 링크로 오인하지 않는다', () => {
    const out = render('[[not closed')
    expect(out).not.toContain('data-wikilink')
  })
})

describe('stripFrontmatter', () => {
  it('YAML frontmatter 를 제거한다', () => {
    const src = '---\ntitle: 제목\ntags: [a, b]\n---\n\n# 본문'
    expect(stripFrontmatter(src)).toBe('\n# 본문')
  })

  it('frontmatter 가 없으면 그대로 둔다', () => {
    const src = '# 본문\n\n내용'
    expect(stripFrontmatter(src)).toBe(src)
  })

  it('본문 중간의 수평선을 frontmatter 로 오인하지 않는다', () => {
    const src = '# 제목\n\n---\n\n본문'
    expect(stripFrontmatter(src)).toBe(src)
  })

  it('닫히지 않은 frontmatter 는 제거하지 않는다', () => {
    const src = '---\ntitle: 제목\n\n# 본문'
    expect(stripFrontmatter(src)).toBe(src)
  })
})

describe('normalizeCallouts', () => {
  it('비표준 warn 을 warning 으로 별칭 처리한다', () => {
    expect(normalizeCallouts('> [!warn] 제목')).toBe('> [!warning] 제목')
  })

  it('대소문자를 가리지 않는다', () => {
    expect(normalizeCallouts('> [!WARN]')).toBe('> [!warning]')
  })

  it('표준 타입은 건드리지 않는다', () => {
    const src = '> [!note] 제목\n> [!tip]\n> [!important]'
    expect(normalizeCallouts(src)).toBe(src)
  })
})
