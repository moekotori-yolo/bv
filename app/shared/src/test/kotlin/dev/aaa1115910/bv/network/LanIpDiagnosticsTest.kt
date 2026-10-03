package dev.aaa1115910.bv.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 固化"旧实现会取到哪个地址"这一事实。
 *
 * 背景：`GeetestCompanionService.resolveLocalIpAddress` 在 Android TV 上几乎总是走
 * `NetworkInterface.getNetworkInterfaces()` 枚举分支（AOSP recovery 源码：
 * `// Android TV defaults to eth0 for it's interface`），而该分支不检查 isUp()、
 * 不过滤接口名、不排序 —— 本测试用的假数据就是把那个缺陷显式化。
 */
class LanIpDiagnosticsTest {

    private fun report(
        name: String,
        addresses: List<String>,
        isUp: Boolean = true,
        isRunning: Boolean = true,
        index: Int = 1,
    ) = LanIpDiagnostics.InterfaceReport(
        name = name,
        index = index,
        isUp = isUp,
        isLoopback = false,
        isRunning = isRunning,
        supportsMulticast = true,
        addresses = addresses,
    )

    @Test
    fun legacyPickedAddressIsFirstOfRawEnumerationOrder() {
        // AOSP getAll() 用 HashMap 按接口名散列后 values() 输出，顺序是桶顺序。
        // 模拟 ap0 排在 eth0 前面 —— 这正是热点地址 192.168.43.1 被误取的路径。
        val ifaces = listOf(
            report("ap0", listOf("192.168.43.1 [v4/site]"), index = 2),
            report("eth0", listOf("192.168.1.23 [v4/site]"), index = 3),
        )
        // 旧实现取的是"枚举顺序里的第一个非 loopback IPv4"，不做任何排序
        assertEquals("192.168.43.1 [v4/site]", ifaces.first().legacyPickedAddress)
    }

    @Test
    fun linkDownInterfaceStillReportsItsStaleAddress() {
        // isUp() 要求 IFF_UP | IFF_RUNNING 同时置位；没插线的 eth0 有 IFF_UP 但没 IFF_RUNNING。
        // 旧实现不查 isUp()，所以这条降级链路上的旧地址照样会被取走。
        val down = report("eth0", listOf("169.254.10.20 [v4/link]"), isUp = false, isRunning = false)
        assertFalse(down.isUp)
        assertEquals("169.254.10.20 [v4/link]", down.legacyPickedAddress)
    }

    @Test
    fun formatExposesBothIsUpFlagsForFieldComparison() {
        val line = report(
            name = "eth0",
            addresses = listOf("192.168.1.23 [v4/site]"),
            isUp = false,
            isRunning = false,
            index = 3,
        ).format()
        assertTrue(line.contains("idx=3"), line)
        assertTrue(line.contains("up=false"), line)
        assertTrue(line.contains("RUNNING=false"), line)
        assertTrue(line.contains("192.168.1.23"), line)
    }

    @Test
    fun interfaceWithoutAddressYieldsNoLegacyPick() {
        val empty = report("rmnet_data0", emptyList())
        assertEquals(null, empty.legacyPickedAddress)
        assertTrue(empty.format().contains("<none>"))
    }
}
