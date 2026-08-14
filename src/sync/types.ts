// 동기화 엔진의 핵심 타입. 설계 문서 2.5절 참조.

/** GitHub Git Tree API 응답의 blob 한 건. */
export type RemoteEntry = {
  path: string
  /** blob SHA. 내용 해시이므로 이 값만 비교하면 변경 여부를 알 수 있다. */
  sha: string
  size: number
}

/** 로컬 files 테이블의 한 행. */
export type LocalFile = {
  path: string
  blobSha: string
}

/** planSync 의 출력. 무엇을 받고 무엇을 지울지. */
export type SyncPlan = {
  download: RemoteEntry[]
  delete: string[]
}

/** blob API 의 파일당 제한. 초과 파일은 건너뛴다. */
export const MAX_BLOB_BYTES = 100 * 1024 * 1024
