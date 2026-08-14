import { Directory, File, Paths } from 'expo-file-system'

/**
 * 볼트 파일 IO. runSync 가 이 인터페이스만 알도록 분리한다 — 테스트에서
 * 메모리 구현으로 대체한다.
 */
export interface VaultFs {
  /**
   * 임시 파일에 쓴 뒤 목적지로 이름을 바꾼다 (원자적).
   * 파일은 "없거나 완전하거나" 둘 중 하나다. 설계 문서 6장 중단 복구.
   */
  writeFileAtomic(relPath: string, data: Uint8Array): Promise<void>
  deleteFile(relPath: string): Promise<void>
  /** WebView base URL 용. 볼트 루트의 file:// URI. */
  vaultRootUri(): string
  readTextFile(relPath: string): Promise<string>
  exists(relPath: string): boolean
}

const VAULT_DIR = 'vault'
const TMP_SUFFIX = '.nv-tmp'

export function createVaultFs(): VaultFs {
  const root = new Directory(Paths.document, VAULT_DIR)

  function fileAt(relPath: string): File {
    return new File(root, relPath)
  }

  return {
    async writeFileAtomic(relPath, data) {
      const dest = fileAt(relPath)
      dest.parentDirectory.create({ intermediates: true, idempotent: true })

      const tmp = new File(dest.parentDirectory, dest.name + TMP_SUFFIX)
      if (tmp.exists) tmp.delete()
      tmp.create()
      tmp.write(data)

      if (dest.exists) dest.delete()
      await tmp.move(dest)
    },

    async deleteFile(relPath) {
      const file = fileAt(relPath)
      if (file.exists) file.delete()
      // 빈 부모 폴더 정리는 하지 않는다 — 비용 대비 이득이 없다.
    },

    vaultRootUri() {
      return root.uri
    },

    async readTextFile(relPath) {
      return fileAt(relPath).text()
    },

    exists(relPath) {
      return fileAt(relPath).exists
    },
  }
}
