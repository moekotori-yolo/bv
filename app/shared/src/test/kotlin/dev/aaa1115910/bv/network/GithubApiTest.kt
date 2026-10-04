package dev.aaa1115910.bv.network

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 冒烟测试：直接打真实 GitHub API，不做任何 mock。
 *
 * 这类测试不是单元测试 —— 它依赖网络、仓库是否已有 release / pre-release，
 * 因此在 fork 或刚改完 OWNER 时必然失败（例如仓库只有正式版、
 * 没有 prerelease 时 getLatestPreReleaseBuild 抛 "No pre-release found"）。
 *
 * 保留它的唯一价值是人工确认线上接口可用，所以默认跳过；
 * 需要时用 -Dneko.smoke=true 打开。
 */
class GithubApiTest {

    private val enabled = System.getProperty("neko.smoke") == "true"

    @Test
    fun `get latest release build`() {
        if (!enabled) return
        runBlocking {
            val release = GithubApi.getLatestReleaseBuild()
            println(release)
            assertTrue(release.tagName.isNotBlank())
        }
    }

    @Test
    fun `get latest pre-release build`() {
        if (!enabled) return
        runBlocking {
            println(GithubApi.getLatestPreReleaseBuild())
        }
    }
}