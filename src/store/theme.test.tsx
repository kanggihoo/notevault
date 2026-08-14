/**
 * 테마 전환 회귀 테스트.
 *
 * react-native-css 는 Appearance.setColorScheme 의 변경 이벤트를 받지 못한다
 * (5.0 preview 시점 실측). 그래서 settings 스토어의 applyTheme 이 Appearance 와
 * css 쪽 colorScheme.set 을 **둘 다** 호출해야 한다 — 하나라도 빠지면
 * 헤더(RN useColorScheme)만 바뀌고 className 배경은 라이트로 고정된다.
 *
 * react-native-css/jest 의 registerCSS 를 쓰지 않는 이유: 그 진입점만
 * react-native 조건이 원본 .ts 를 가리켜 tsc 가 남의 소스를 검사하게 된다.
 * 같은 일을 compiler + StyleCollection 으로 직접 한다.
 */
import { Appearance, View } from 'react-native'
import { act, render, screen } from '@testing-library/react-native'
import { compile } from 'react-native-css/compiler'
import { useCssElement } from 'react-native-css'
import { colorScheme } from 'react-native-css/native'
import { StyleCollection } from 'react-native-css/native-internal'

const Styled = (props: { className?: string; testID?: string }) =>
  useCssElement(View, props, { className: 'style' })

const CSS = `
  :root { --nv-bg: light-dark(#ffffff, #1e1e1e); }
  .bg-background { background-color: var(--nv-bg); }
`

beforeEach(() => {
  StyleCollection.styles.clear()
  StyleCollection.inject(compile(CSS).stylesheet())
  colorScheme.set('light')
})

function backgroundOf(): unknown {
  const style = screen.getByTestId('t').props.style as { backgroundColor?: string }
  return style.backgroundColor
}

test('css colorScheme.set 이 light-dark 토큰을 뒤집는다 (applyTheme 의 근거)', () => {
  render(<Styled testID="t" className="bg-background" />)
  expect(backgroundOf()).toBe('#fff')

  act(() => colorScheme.set('dark'))
  expect(backgroundOf()).toBe('#1e1e1e')

  act(() => colorScheme.set('light'))
  expect(backgroundOf()).toBe('#fff')
})

test('Appearance.setColorScheme 단독으로는 갱신되지 않는다 — 이중 호출이 필요한 이유', () => {
  render(<Styled testID="t" className="bg-background" />)
  expect(backgroundOf()).toBe('#fff')

  act(() => {
    Appearance.setColorScheme('dark')
  })
  // 이 단언이 깨지면(= 라이브러리가 고쳐지면) applyTheme 의 이중 호출을 걷어낼 수 있다.
  expect(backgroundOf()).toBe('#fff')
})

test('inlineVariables:false 는 light-dark 의 다크 값을 소실시킨다 — metro 옵션 금지 근거', () => {
  // metro.config.js 에서 이 옵션을 다시 켜면 폰에서 테마 전환이 죽는다.
  // 이 단언이 깨지면(= 컴파일러가 고쳐지면) 옵션을 재검토할 수 있다.
  const compiled = compile(CSS, { inlineVariables: false } as never)
  const sheet = compiled.stylesheet() as { vr?: [string, unknown[]][] }
  const nvBg = sheet.vr?.find(([name]) => name === 'nv-bg')
  expect(JSON.stringify(nvBg)).not.toContain('#1e1e1e')
})
