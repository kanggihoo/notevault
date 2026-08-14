/**
 * 렌더러 번들 빌드.
 *
 *   node assets/renderer/build.mjs        (npm run renderer:build)
 *
 * 1. global.css 에서 --nv- 변수 블록을 추출한다 — 색의 단일 출처.
 *    :root       → html[data-nv-theme='light']
 *    .dark:root  → html[data-nv-theme='dark']
 * 2. esbuild 로 src/index.ts 를 단일 JS + CSS 로 번들한다.
 *    KaTeX 폰트는 dataurl 로 인라인해 파일 하나로 완결시킨다.
 * 3. JS·CSS 를 인라인한 dist/index.html 을 만든다. WebView 가 이 파일
 *    하나만 로드하면 되고, base URL 은 볼트 루트를 가리킬 수 있다.
 *
 * CDN 참조가 없으므로 오프라인에서 완전히 동작한다.
 */
import { build } from 'esbuild'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const root = join(here, '..', '..')
const dist = join(here, 'dist')

// ── 1. global.css 에서 테마 변수 추출 ─────────────────────────────
// 토큰은 `--nv-x: light-dark(라이트값, 다크값)` 형식이다 (단일 출처).
// 렌더러는 data-nv-theme 속성으로 테마를 전환하므로 두 블록으로 펼친다.
const globalCss = await readFile(join(root, 'global.css'), 'utf8')

/** light-dark(A, B) 의 최상위 콤마에서 분리한다. rgba(...) 중첩을 견딘다. */
function splitTopLevel(inner) {
  let depth = 0
  for (let i = 0; i < inner.length; i++) {
    const ch = inner[i]
    if (ch === '(') depth++
    else if (ch === ')') depth--
    else if (ch === ',' && depth === 0) {
      return [inner.slice(0, i).trim(), inner.slice(i + 1).trim()]
    }
  }
  throw new Error(`light-dark 콤마를 찾지 못했다: ${inner}`)
}

const light = []
const dark = []
const varPattern = /--nv-([\w-]+):\s*([^;]+);/g
for (const match of globalCss.matchAll(varPattern)) {
  const [, name, rawValue] = match
  const value = rawValue.trim()
  const ld = /^light-dark\((.*)\)$/s.exec(value)
  if (ld) {
    const [lightValue, darkValue] = splitTopLevel(ld[1])
    light.push(`--nv-${name}: ${lightValue};`)
    dark.push(`--nv-${name}: ${darkValue};`)
  } else if (!value.startsWith('var(')) {
    // 단일 값은 양쪽 테마에 그대로 (var() 참조는 @theme 매핑이므로 제외)
    light.push(`--nv-${name}: ${value};`)
    dark.push(`--nv-${name}: ${value};`)
  }
}
if (light.length === 0) throw new Error('global.css 에서 --nv- 토큰을 찾지 못했다')

const themeVars = [
  `html[data-nv-theme='light'] {\n  ${light.join('\n  ')}\n}`,
  `html[data-nv-theme='dark'] {\n  ${dark.join('\n  ')}\n}`,
].join('\n\n')

// ── 2. esbuild 번들 ───────────────────────────────────────────────
await mkdir(dist, { recursive: true })

const result = await build({
  entryPoints: [join(here, 'src', 'index.ts')],
  bundle: true,
  format: 'iife',
  platform: 'browser',
  target: 'es2020',
  minify: true,
  outfile: join(dist, 'renderer.js'),
  loader: {
    '.woff2': 'dataurl',
    '.woff': 'empty', // woff2 만 있으면 된다 — 안드로이드 WebView 는 전부 지원
    '.ttf': 'empty',
  },
  logLevel: 'silent',
  metafile: true,
})

const js = await readFile(join(dist, 'renderer.js'), 'utf8')
const css = await readFile(join(dist, 'renderer.css'), 'utf8')

// ── 3. 단일 HTML 로 합성 ──────────────────────────────────────────
// </script> 가 JS 문자열 안에 있으면 파서가 태그를 닫아버리므로 이스케이프한다.
const safeJs = js.replace(/<\/script>/gi, '<\\/script>')

const html = `<!DOCTYPE html>
<html data-nv-theme="dark">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
<style>
${themeVars}
${css}
</style>
</head>
<body>
<div id="nv-root"></div>
<script>
${safeJs}
</script>
</body>
</html>
`

await writeFile(join(dist, 'index.html'), html)

const mb = (n) => (n / 1024 / 1024).toFixed(2) + 'MB'
console.log(`renderer.js   ${mb(js.length)}`)
console.log(`renderer.css  ${mb(css.length)}`)
console.log(`index.html    ${mb(html.length)}  (JS·CSS 인라인)`)
