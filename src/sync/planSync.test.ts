import { isDotPath, isSubscribed, planSync } from './planSync'
import { MAX_BLOB_BYTES, type LocalFile, type RemoteEntry } from './types'

const r = (path: string, sha: string, size = 100): RemoteEntry => ({ path, sha, size })
const l = (path: string, blobSha: string): LocalFile => ({ path, blobSha })

describe('isDotPath', () => {
  it('점으로 시작하는 세그먼트를 걸러낸다', () => {
    expect(isDotPath('.obsidian/app.json')).toBe(true)
    expect(isDotPath('Dev/.claude/x.md')).toBe(true)
    expect(isDotPath('.github/workflows/sync.yml')).toBe(true)
  })

  it('점이 세그먼트 시작이 아니면 통과시킨다', () => {
    expect(isDotPath('Dev/v1.2/note.md')).toBe(false)
    expect(isDotPath('postgresql/01-shared_buffer.md')).toBe(false)
    expect(isDotPath('기업조사/attachments/Pasted image 1.png')).toBe(false)
  })
})

describe('isSubscribed', () => {
  it('부모 폴더를 구독하면 자식 전체가 포함된다', () => {
    const subs = new Set(['Dev'])
    expect(isSubscribed('Dev/K8s/pod.md', subs)).toBe(true)
    expect(isSubscribed('Dev/attachments/a.png', subs)).toBe(true)
  })

  it('폴더 경계를 지킨다 (prefix 문자열 일치가 아니다)', () => {
    expect(isSubscribed('Development/x.md', new Set(['Dev']))).toBe(false)
  })

  it('구독하지 않은 폴더는 제외한다', () => {
    expect(isSubscribed('Archive/x.md', new Set(['Dev']))).toBe(false)
  })
})

describe('planSync', () => {
  it('신규 파일을 다운로드한다', () => {
    const plan = planSync([r('Dev/a.md', 'sha1')], [], ['Dev'])
    expect(plan.download.map((e) => e.path)).toEqual(['Dev/a.md'])
    expect(plan.delete).toEqual([])
  })

  it('blob SHA 가 바뀌면 재다운로드한다', () => {
    const plan = planSync([r('Dev/a.md', 'new')], [l('Dev/a.md', 'old')], ['Dev'])
    expect(plan.download.map((e) => e.path)).toEqual(['Dev/a.md'])
  })

  it('blob SHA 가 같으면 스킵한다 — 증분의 핵심', () => {
    const plan = planSync([r('Dev/a.md', 'same')], [l('Dev/a.md', 'same')], ['Dev'])
    expect(plan.download).toEqual([])
    expect(plan.delete).toEqual([])
  })

  it('원격에서 삭제된 파일을 로컬에서 지운다', () => {
    const plan = planSync([], [l('Dev/gone.md', 'sha1')], ['Dev'])
    expect(plan.delete).toEqual(['Dev/gone.md'])
  })

  it('구독 해제 시 해당 prefix 하위 전체를 지운다', () => {
    const remote = [r('Dev/a.md', 's1'), r('Archive/b.md', 's2')]
    const local = [l('Dev/a.md', 's1'), l('Archive/b.md', 's2')]
    // Archive 구독을 해제한 상태
    const plan = planSync(remote, local, ['Dev'])
    expect(plan.delete).toEqual(['Archive/b.md'])
    expect(plan.download).toEqual([])
  })

  it('폴더 이름 변경을 삭제 + 신규로 처리한다', () => {
    const plan = planSync(
      [r('Dev/new/a.md', 'same-content')],
      [l('Dev/old/a.md', 'same-content')],
      ['Dev'],
    )
    expect(plan.download.map((e) => e.path)).toEqual(['Dev/new/a.md'])
    expect(plan.delete).toEqual(['Dev/old/a.md'])
  })

  it('중단 복구: 47개 중 12개 완료 상태에서 35개만 요청한다', () => {
    const remote = Array.from({ length: 47 }, (_, i) => r(`Dev/f${i}.md`, `sha${i}`))
    const local = remote.slice(0, 12).map((e) => l(e.path, e.sha))
    const plan = planSync(remote, local, ['Dev'])
    expect(plan.download).toHaveLength(35)
    expect(plan.delete).toEqual([])
  })

  it('점 경로를 제외한다', () => {
    const remote = [
      r('.obsidian/app.json', 's1'),
      r('Dev/.claude/x.md', 's2'),
      r('Dev/note.md', 's3'),
    ]
    const plan = planSync(remote, [], ['Dev', '.obsidian'])
    expect(plan.download.map((e) => e.path)).toEqual(['Dev/note.md'])
  })

  it('이미 받은 점 경로 파일은 삭제 대상이 된다', () => {
    const plan = planSync([r('.obsidian/app.json', 's1')], [l('.obsidian/app.json', 's1')], [''])
    expect(plan.delete).toEqual(['.obsidian/app.json'])
  })

  it('blob API 제한(100MB) 초과 파일을 건너뛴다', () => {
    const remote = [
      r('Dev/huge.zip', 's1', MAX_BLOB_BYTES + 1),
      r('Dev/ok.md', 's2', MAX_BLOB_BYTES),
    ]
    const plan = planSync(remote, [], ['Dev'])
    expect(plan.download.map((e) => e.path)).toEqual(['Dev/ok.md'])
  })

  it('구독 폴더의 attachments 가 자동으로 포함된다', () => {
    const remote = [
      r('기업조사/note.md', 's1'),
      r('기업조사/attachments/Pasted image 1.png', 's2'),
    ]
    const plan = planSync(remote, [], ['기업조사'])
    expect(plan.download).toHaveLength(2)
  })
})
