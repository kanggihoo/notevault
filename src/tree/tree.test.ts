import { buildLocalTree, filterTree } from './localTree'
import {
  buildRemoteTree,
  formatBytes,
  subscriptionState,
  toggleSubscription,
} from './remoteTree'
import type { RemoteEntry } from '../sync/types'

describe('buildLocalTree', () => {
  it('경로 목록을 폴더 우선·이름순 트리로 만든다', () => {
    const tree = buildLocalTree([
      'Dev/K8s/pod.md',
      'Dev/a.md',
      'Archive/b.md',
      'root.md',
    ])
    expect(tree.map((n) => n.name)).toEqual(['Archive', 'Dev', 'root.md'])
    const dev = tree[1]
    expect(dev.children!.map((n) => n.name)).toEqual(['K8s', 'a.md'])
  })

  it('필터는 매치한 파일과 조상 폴더만 남긴다', () => {
    const tree = buildLocalTree(['Dev/K8s/pod.md', 'Dev/K8s/svc.md', 'Dev/a.md'])
    const filtered = filterTree(tree, 'pod')
    expect(filtered).toHaveLength(1)
    expect(filtered[0].children![0].children!.map((n) => n.name)).toEqual(['pod.md'])
  })

  it('한글 파일명 필터가 동작한다', () => {
    const tree = buildLocalTree(['기업조사/취업관련 raw.md', '기업조사/다른것.md'])
    const filtered = filterTree(tree, '취업')
    expect(filtered[0].children).toHaveLength(1)
  })
})

describe('buildRemoteTree', () => {
  const r = (path: string, size: number): RemoteEntry => ({ path, sha: 'x', size })

  it('폴더별 노트·이미지·용량을 조상까지 집계한다', () => {
    const tree = buildRemoteTree([
      r('기업조사/note.md', 1000),
      r('기업조사/attachments/a.png', 800_000),
      r('기업조사/attachments/b.png', 200_000),
    ])
    const folder = tree[0]
    expect(folder.name).toBe('기업조사')
    expect(folder.noteCount).toBe(1)
    expect(folder.imageCount).toBe(2)
    expect(folder.totalBytes).toBe(1_001_000)
    const attachments = folder.children[0]
    expect(attachments.imageCount).toBe(2)
    expect(attachments.noteCount).toBe(0)
  })

  it('점 경로를 제외한다', () => {
    const tree = buildRemoteTree([r('.obsidian/app.json', 10), r('Dev/a.md', 10)])
    expect(tree.map((n) => n.name)).toEqual(['Dev'])
  })
})

describe('formatBytes', () => {
  it.each([
    [500, '500B'],
    [1536, '1.5KB'],
    [29.3 * 1024 * 1024, '29.3MB'],
  ])('%d → %s', (input, expected) => {
    expect(formatBytes(input)).toBe(expected)
  })
})

describe('toggleSubscription', () => {
  it('부모를 구독하면 자식 구독이 정리된다', () => {
    const next = toggleSubscription(['Dev/K8s', 'Archive'], 'Dev')
    expect(next.sort()).toEqual(['Archive', 'Dev'])
  })

  it('구독 해제는 해당 경로만 제거한다', () => {
    expect(toggleSubscription(['Dev', 'Archive'], 'Dev').sort()).toEqual(['Archive'])
  })

  it('볼트 전체 구독은 모든 개별 구독을 정리한다', () => {
    expect(toggleSubscription(['Dev', 'Archive'], '')).toEqual([''])
  })
})

describe('subscriptionState', () => {
  const subs = ['Dev']
  it('직접 구독 → checked', () => {
    expect(subscriptionState(subs, 'Dev')).toBe('checked')
  })
  it('조상이 구독 → inherited', () => {
    expect(subscriptionState(subs, 'Dev/K8s')).toBe('inherited')
  })
  it('자손에 구독 → partial', () => {
    expect(subscriptionState(['Dev/K8s'], 'Dev')).toBe('partial')
  })
  it('무관 → none', () => {
    expect(subscriptionState(subs, 'Archive')).toBe('none')
  })
  it('폴더 경계를 지킨다 — Dev 구독이 Development 를 상속시키지 않는다', () => {
    expect(subscriptionState(subs, 'Development')).toBe('none')
  })
})
