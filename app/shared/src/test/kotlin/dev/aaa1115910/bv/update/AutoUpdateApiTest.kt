package dev.aaa1115910.bv.update

import dev.aaa1115910.bv.network.entity.Release
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 更新检查的解析规则。
 *
 * 真实数据取自本仓库 v0.4.0 Release：
 *   BV_1378_0.3.0.r1378.22a1b744.release_default_universal.apk
 * 其中 1378 = AppConfiguration.resolveVersion() 的 git 提交总数，
 * 与安装包的 BuildConfig.VERSION_CODE 同源。
 */
class AutoUpdateApiTest {

    @Test
    fun parsesRealCiApkName() {
        val parsed = AutoUpdateApi.parseApkName(
            "BV_1378_0.3.0.r1378.22a1b744.release_default_universal.apk"
        )
        assertEquals("0.3.0.r1378.22a1b744" to 1378, parsed)
    }

    @Test
    fun stripsBuildTypeSuffixForEveryVariant() {
        assertEquals(
            "0.3.0.r1378.abc" to 1378,
            AutoUpdateApi.parseApkName("BV_1378_0.3.0.r1378.abc.alpha_default_universal.apk"),
        )
        assertEquals(
            "0.3.0.r1378.abc" to 1378,
            AutoUpdateApi.parseApkName("BV_1378_0.3.0.r1378.abc.debug_default_universal.apk"),
        )
        assertEquals(
            "0.3.0.r1378.abc" to 1378,
            AutoUpdateApi.parseApkName("BV_1378_0.3.0.r1378.abc.r8Test_default_universal.apk"),
        )
    }

    @Test
    fun rejectsUnexpectedAssetNames() {
        // mapping.zip 不是 APK
        assertNull(AutoUpdateApi.parseApkName("mapping.zip"))
        // 缺少 BV_ 前缀
        assertNull(AutoUpdateApi.parseApkName("app-release.apk"))
        // 版本名段为空
        assertNull(AutoUpdateApi.parseApkName("BV_1378_release_default_universal.apk"))
    }

    @Test
    fun versionCodeFallsBackToRevisionMarker() {
        // 没有 BV_ 前缀时用 r<code>
        assertEquals(1378, AutoUpdateApi.parseVersionCode("0.3.0.r1378.22a1b744"))
    }

    @Test
    fun buildsInfoFromReleaseAssetsAndBody() {
        val release = release(
            assets = listOf(
                asset("mapping.zip"),
                asset("BV_1378_0.3.0.r1378.22a1b744.release_default_universal.apk"),
            ),
            body = "- 修复验证码面板裁切\n- 补 ACCESS_LOCAL_NETWORK 权限",
            htmlUrl = "https://github.com/moekotori-yolo/bv/releases/tag/v0.4.0",
        )
        val info = AutoUpdateApi.parseRelease(release)
        assertEquals("0.3.0.r1378.22a1b744", info.versionName)
        assertEquals(1378, info.versionCode)
        // changelog 直接用 Release 正文
        assertEquals("- 修复验证码面板裁切\n- 补 ACCESS_LOCAL_NETWORK 权限", info.changelog)
        assertEquals("https://github.com/moekotori-yolo/bv/releases/tag/v0.4.0", info.downloadPageUrl)
    }

    @Test
    fun fallsBackToTagWhenNoApkAsset() {
        // 只有源码 tarball 的 Release 不应让检查失败。
        val release = release(
            assets = emptyList(),
            body = "   ",
            htmlUrl = "",
            tagName = "v0.3.0.r1378",
        )
        val info = AutoUpdateApi.parseRelease(release)
        assertEquals("0.3.0.r1378", info.versionName)
        assertEquals(1378, info.versionCode)
        assertEquals("暂无更新内容", info.changelog)
        assertEquals(AutoUpdateApi.GITHUB_RELEASE_PAGE_URL, info.downloadPageUrl)
    }

    private fun asset(name: String) = Release.Asset(
        browserDownloadUrl = "https://example.com/$name",
        contentType = "",
        createdAt = "",
        downloadCount = 0,
        id = 0,
        name = name,
        nodeId = "",
        size = 0,
        state = "uploaded",
        updatedAt = "",
        uploader = user(),
        url = "",
    )

    private fun user() = Release.User(
        avatarUrl = "", eventsUrl = "", followersUrl = "", followingUrl = "",
        gistsUrl = "", gravatarId = "", htmlUrl = "", id = 0, login = "",
        nodeId = "", organizationsUrl = "", receivedEventsUrl = "", reposUrl = "",
        siteAdmin = false, starredUrl = "", subscriptionsUrl = "", type = "User", url = "",
    )

    private fun release(
        assets: List<Release.Asset>,
        body: String,
        htmlUrl: String,
        tagName: String = "v0.4.0",
    ) = Release(
        assets = assets,
        assetsUrl = "",
        author = user(),
        body = body,
        createdAt = "",
        draft = false,
        htmlUrl = htmlUrl,
        id = 0,
        name = tagName,
        nodeId = "",
        prerelease = false,
        publishedAt = "",
        reactions = null,
        tagName = tagName,
        tarballUrl = "",
        targetCommitish = "master",
        uploadUrl = "",
        url = "",
        zipballUrl = "",
    )
}