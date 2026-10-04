package dev.aaa1115910.bv.update

import dev.aaa1115910.bv.BuildConfig
import dev.aaa1115910.bv.network.GithubApi
import dev.aaa1115910.bv.network.entity.Release
import dev.aaa1115910.bv.util.Prefs
import java.util.Calendar

data class AutoUpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val changelog: String,
    val downloadPageUrl: String = AutoUpdateApi.GITHUB_RELEASE_PAGE_URL
)

/**
 * 更新检查。
 *
 * 早期实现读取上游托管在 R2 上的 `release.txt`，那个桶不在我们手里；
 * 现在改为直接读自己仓库的 GitHub Releases，与 [GithubApi.downloadUpdate]
 * 用的是同一份数据源，不会出现「检查到的版本」和「实际下载的包」不一致。
 *
 * 版本号来自 APK 资产名 —— CI 用
 * `BV_<versionCode>_<versionName>.<buildType>_<flavor>_<abi>.apk`
 * 命名，其中 versionCode 就是 `AppConfiguration.resolveVersion()` 里
 * 的 git 提交总数，与安装包的 BuildConfig.VERSION_CODE 同源。
 */
object AutoUpdateApi {
    const val GITHUB_RELEASE_PAGE_URL = "https://github.com/moekotori-yolo/bv/releases/latest"

    /**
     * APK 资产名里形如 `.release` / `.alpha` / `.debug` 的构建类型段。
     * 构建类型名沿用 Gradle 的大小写（`r8Test` 是驼峰），所以比较要忽略大小写，
     * 否则 `r8Test` 不会被剥离、会被当成版本名的一部分。
     */
    private val BUILD_TYPE_SUFFIXES =
        setOf("release", "alpha", "debug", "r8test")

    /** `BV_<code>_<version>...` 开头，code 是 git 提交数。 */
    private val APK_NAME_REGEX = Regex("""^BV_(\d+)_""")

    suspend fun getLatestUpdateInfo(): AutoUpdateInfo {
        val release = GithubApi.getLatestBuild()
        return parseRelease(release)
    }

    /**
     * 从 Release 里解析更新信息。抽成纯函数是为了能直接断言解析规则。
     *
     * 优先从 APK 资产名取版本号 —— 它由 CI 生成，和 APK 内部版本严格一致。
     * 只有在没有 APK 资产时才退回 tag（例如仓库里只有源码 tarball）。
     */
    fun parseRelease(release: Release): AutoUpdateInfo {
        val apk = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }

        val versionName: String
        val versionCode: Int
        if (apk != null) {
            val parsed = parseApkName(apk.name)
            if (parsed != null) {
                versionName = parsed.first
                versionCode = parsed.second
            } else {
                // 资产名不符合预期格式时退回 tag，避免整个检查失败。
                versionName = release.tagName.removePrefix("v")
                versionCode = parseVersionCode(versionName)
            }
        } else {
            versionName = release.tagName.removePrefix("v")
            versionCode = parseVersionCode(versionName)
        }

        return AutoUpdateInfo(
            versionName = versionName,
            versionCode = versionCode,
            changelog = release.body.trim().ifBlank { "暂无更新内容" },
            downloadPageUrl = release.htmlUrl.ifBlank { GITHUB_RELEASE_PAGE_URL },
        )
    }

    /**
     * `BV_1378_0.3.0.r1378.22a1b744.release_default_universal.apk`
     *   -> ("0.3.0.r1378.22a1b744", 1378)
     *
     * 注意段之间用的是 `_` 而不是 `.`：
     * versionName 自身含点号（`0.3.0.r1378.22a1b744`），构建类型是紧随其后
     * 的另一段（`release`），所以必须先按 `_` 切段、再剥掉段尾的构建类型。
     */
    fun parseApkName(apkName: String): Pair<String, Int>? {
        val m = APK_NAME_REGEX.find(apkName) ?: return null
        val code = m.groupValues[1].toIntOrNull() ?: return null

        // 去掉 "BV_<code>_" 前缀后按 `_` 切段。第 0 段是 versionName
        // 与可选的 ".<buildType>"，例如 "0.3.0.r1378.abc.release"。
        val head = apkName.substring(m.range.last + 1).substringBefore('_')
        val dotParts = head.split('.')
        val cleaned = if (dotParts.size >= 2 &&
            dotParts.last().lowercase() in BUILD_TYPE_SUFFIXES
        ) {
            dotParts.dropLast(1).joinToString(".")
        } else {
            head
        }
        // 剥离构建类型后必须还剩下真正的版本号；只剩构建类型本身说明该资产
        // 不是我们 CI 产出的命名格式（例如有人手工上传的 app-release.apk）。
        if (cleaned.isBlank() || cleaned.lowercase() in BUILD_TYPE_SUFFIXES) return null
        return cleaned to code
    }

    /**
     * 从任意版本字符串里取 versionCode。
     * CI 的 `r<code>` 形式（versionName 里带的提交数）与 APK 名前缀都能解析。
     */
    fun parseVersionCode(versionName: String): Int {
        // 兼容直接传整串 APK 文件名的情况。
        APK_NAME_REGEX.find(versionName)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?.let { return it }

        val revisionVersionCode = Regex("""(?:^|[._-])r(\d+)(?:[._-]|$)""")
            .find(versionName)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        check(revisionVersionCode != null) { "Cannot parse version code from $versionName" }
        return revisionVersionCode
    }
}

object AutoUpdateChecker {
    suspend fun checkOnceDaily(): AutoUpdateInfo? {
        val today = currentDayKey()
        if (!BuildConfig.DEBUG && Prefs.lastAutoUpdateCheckDay == today) return null

        if (!BuildConfig.DEBUG) {
            Prefs.lastAutoUpdateCheckDay = today
        }
        val updateInfo = AutoUpdateApi.getLatestUpdateInfo()
        return updateInfo.takeIf {
            BuildConfig.DEBUG || it.versionCode > BuildConfig.VERSION_CODE
        }
    }

    private fun currentDayKey(): Long {
        val calendar = Calendar.getInstance()
        return calendar.get(Calendar.YEAR) * 1000L + calendar.get(Calendar.DAY_OF_YEAR)
    }
}