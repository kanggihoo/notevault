/**
 * WebView 안에서 실행되는 렌더러 엔트리.
 *
 * RN → WebView 메시지
 *   { type: 'render', markdown: string, noteDir: string, theme: 'dark'|'light' }
 *
 * WebView → RN 메시지
 *   { type: 'ready' }                          렌더러 초기화 완료
 *   { type: 'wikilink', target: string }       위키링크 탭
 *   { type: 'external', href: string }         외부 링크 탭 → 시스템 브라우저
 *   { type: 'error', message: string }         렌더 실패
 *
 * base URL 은 볼트 루트로 고정한다. 노트가 바뀔 때마다 WebView 를 리로드하지
 * 않기 위해서다. 상대 경로 이미지는 render 시점에 noteDir 를 붙여 해석한다.
 */
import MarkdownIt from 'markdown-it'
import calloutsPkg from 'markdown-it-obsidian-callouts'
import hljs from 'highlight.js/lib/common'
import mermaid from 'mermaid'
import renderMathInElement from 'katex/contrib/auto-render'

import { normalizeCallouts, stripFrontmatter, wikiEmbed } from './wikiEmbed'
import { mermaidFence, obsidianMark, tableWrap } from './obsidianExtras'

import 'katex/dist/katex.min.css'
import 'highlight.js/styles/github-dark.css'
import './theme.css'

// CJS/ESM 어느 쪽으로 번들되든 동작하도록.
const callouts = (calloutsPkg as any).default ?? calloutsPkg

declare global {
  interface Window {
    ReactNativeWebView?: { postMessage(data: string): void }
  }
}

type RenderMessage = {
  type: 'render'
  markdown: string
  /** 노트가 위치한 폴더의 볼트 상대 경로. 루트 노트면 '' */
  noteDir: string
  theme: 'dark' | 'light'
}

function post(message: object): void {
  window.ReactNativeWebView?.postMessage(JSON.stringify(message))
}

const md = new MarkdownIt({
  html: true,
  linkify: true,
  highlight(code, lang) {
    if (lang && hljs.getLanguage(lang)) {
      try {
        return hljs.highlight(code, { language: lang, ignoreIllegals: true }).value
      } catch {
        /* 하이라이팅 실패는 무시하고 평문으로 */
      }
    }
    return ''
  },
})
  .use(callouts)
  .use(wikiEmbed)
  .use(obsidianMark)
  .use(tableWrap)
  .use(mermaidFence)

const root = document.getElementById('nv-root')!
let mermaidTheme: 'dark' | 'default' | null = null

function applyTheme(theme: 'dark' | 'light'): void {
  document.documentElement.dataset.nvTheme = theme
  const next = theme === 'dark' ? 'dark' : 'default'
  if (next !== mermaidTheme) {
    mermaidTheme = next
    // securityLevel strict 는 file:// 이미지 로드를 막을 수 있어 loose 로 둔다.
    // 렌더 대상이 사용자 본인의 볼트뿐이므로 위협 모델에 없다.
    mermaid.initialize({ startOnLoad: false, theme: next, securityLevel: 'loose' })
  }
}

/** 상대 경로 이미지에 noteDir 를 붙인다. base URL 이 볼트 루트이기 때문이다. */
function resolveImages(noteDir: string): void {
  const prefix = noteDir ? noteDir.split('/').map(encodeURIComponent).join('/') + '/' : ''
  root.querySelectorAll<HTMLImageElement>('img').forEach((img) => {
    const src = img.getAttribute('src') ?? ''
    // 절대 URL(http, file, data)은 그대로 둔다.
    if (/^[a-z][a-z0-9+.-]*:/i.test(src) || src.startsWith('/')) return
    img.setAttribute('src', prefix + src)
  })
}

/**
 * 코드블록마다 복사 버튼을 단다. 실제 클립보드 접근은 RN 이 한다.
 * pre 는 가로 스크롤 컨테이너라 버튼을 안에 두면 함께 밀린다 —
 * 래퍼로 감싸고 버튼은 래퍼에 붙여 고정한다.
 */
function installCodeCopyButtons(): void {
  root.querySelectorAll<HTMLElement>('pre > code').forEach((code) => {
    const pre = code.parentElement!
    if (pre.classList.contains('nv-mermaid') || pre.parentElement?.classList.contains('nv-pre-wrap'))
      return

    const wrap = document.createElement('div')
    wrap.className = 'nv-pre-wrap'
    pre.replaceWith(wrap)
    wrap.appendChild(pre)

    const btn = document.createElement('button')
    btn.className = 'nv-copy'
    btn.textContent = '복사'
    btn.addEventListener('click', (e) => {
      e.stopPropagation()
      post({ type: 'copy', text: code.textContent ?? '' })
      btn.textContent = '복사됨'
      setTimeout(() => (btn.textContent = '복사'), 1500)
    })
    wrap.appendChild(btn)
  })
}

/** 이미지 로드 실패 시 깨진 아이콘 대신 자리표시를 보여준다. */
function installImageFallback(): void {
  root.querySelectorAll<HTMLImageElement>('img').forEach((img) => {
    img.addEventListener(
      'error',
      () => {
        const ph = document.createElement('div')
        ph.className = 'nv-img-missing'
        ph.textContent = '이미지 없음'
        img.replaceWith(ph)
      },
      { once: true },
    )
  })
}

async function render(message: RenderMessage): Promise<void> {
  applyTheme(message.theme)

  const source = normalizeCallouts(stripFrontmatter(message.markdown))
  root.innerHTML = md.render(source)

  resolveImages(message.noteDir)
  installImageFallback()
  installCodeCopyButtons()

  // KaTeX — $$ 블록과 $ 인라인 (37개 노트)
  try {
    renderMathInElement(root, {
      delimiters: [
        { left: '$$', right: '$$', display: true },
        { left: '$', right: '$', display: false },
      ],
      throwOnError: false,
    })
  } catch {
    /* 수식 오류가 본문 렌더를 막지 않게 한다 */
  }

  // mermaid — 다이어그램 렌더 실패도 본문을 막지 않는다
  try {
    await mermaid.run({ nodes: Array.from(root.querySelectorAll('.nv-mermaid')) })
  } catch {
    /* 실패한 다이어그램은 원문 코드로 남는다 */
  }

  window.scrollTo(0, 0)
}

// 링크 처리 — 클릭 위임 하나로 전부 받는다.
document.addEventListener('click', (event) => {
  const anchor = (event.target as HTMLElement).closest('a')
  if (!anchor) return
  event.preventDefault()

  const wikilink = anchor.getAttribute('data-wikilink')
  if (wikilink) {
    post({ type: 'wikilink', target: wikilink })
    return
  }
  const href = anchor.getAttribute('href') ?? ''
  if (/^https?:/i.test(href)) {
    // 외부 링크는 WebView 안에서 열지 않고 시스템 브라우저로 넘긴다.
    post({ type: 'external', href })
  }
})

// RN 의 injectJavaScript / postMessage 양쪽 경로 모두 지원한다.
function onMessage(raw: string): void {
  try {
    const message = JSON.parse(raw) as RenderMessage
    if (message.type === 'render') void render(message)
  } catch (error) {
    post({ type: 'error', message: String(error) })
  }
}

// Android WebView 는 document 에, iOS 는 window 에 message 이벤트가 온다.
document.addEventListener('message', (e) => onMessage((e as MessageEvent).data))
window.addEventListener('message', (e) => onMessage((e as MessageEvent).data))

post({ type: 'ready' })
