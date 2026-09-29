package com.ssafy.notevault.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 볼트 파일 IO (RN 판 src/sync/vaultFs.ts 이식). 경로는 볼트 루트 기준 상대 경로다.
 *
 * 앱에서는 [root] 를 앱 전용 폴더(context.filesDir/vault)로 준다 — 저장소 권한이 필요 없다.
 */
class VaultFiles(val root: File) {

    /**
     * 임시 파일에 쓴 뒤 목적지로 이름을 바꾼다. 파일은 "없거나 완전하거나" 둘 중 하나다 —
     * 쓰는 도중 앱이 죽어도 반쯤 쓴 노트가 열리지 않는다.
     */
    suspend fun writeAtomic(relPath: String, data: ByteArray) = withContext(Dispatchers.IO) {
        val dest = resolve(relPath)
        dest.parentFile!!.mkdirs()
        val tmp = File(dest.parentFile, dest.name + TMP_SUFFIX)
        tmp.writeBytes(data)
        Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        Unit
    }

    /** 없는 파일이면 조용히 넘어간다. 빈 부모 폴더 정리는 하지 않는다. */
    suspend fun delete(relPath: String) = withContext(Dispatchers.IO) {
        resolve(relPath).delete()
        Unit
    }

    suspend fun readText(relPath: String): String = withContext(Dispatchers.IO) { resolve(relPath).readText() }

    fun exists(relPath: String): Boolean = resolve(relPath).exists()

    /** 상대 경로 → 실제 파일. `../` 로 볼트 밖을 가리키면 거부한다. */
    fun resolve(relPath: String): File {
        val file = File(root, relPath).canonicalFile
        require(file.path.startsWith(root.canonicalPath + File.separator)) { "볼트 밖 경로: $relPath" }
        return file
    }

    private companion object {
        const val TMP_SUFFIX = ".nv-tmp"
    }
}
