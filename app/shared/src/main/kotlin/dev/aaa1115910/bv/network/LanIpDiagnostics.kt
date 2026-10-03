package dev.aaa1115910.bv.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 局域网地址诊断，**仅供 Debug HUD 展示**，不参与任何生产决策。
 *
 * 存在的意义是把三处推断变成屏幕上的实测数字：
 * 1. [GeetestCompanionService.resolveLocalIpAddress] 在 Android TV 上拿到的是不是 `eth0` 的地址
 *    （AOSP recovery 源码注释：`// Android TV defaults to eth0 for it's interface`）
 * 2. `NetworkInterface.getNetworkInterfaces()` 的实际顺序 —— AOSP `getAll()` 用
 *    `HashMap<String, ...>` 按接口名散列后输出一遍，顺序是桶顺序，不随插入序
 * 3. 热点 `ap0`（192.168.43.1）是否排在 `eth0` 前面被误取
 */
object LanIpDiagnostics {

    private const val IFF_RUNNING = 0x40

    /** 单个网卡的观测结果。所有字段都做了 runCatching 兜底，TV 厂商 ROM 差异很大。 */
    data class InterfaceReport(
        val name: String,
        val index: Int,
        val isUp: Boolean,
        val isLoopback: Boolean,
        val isRunning: Boolean,
        val supportsMulticast: Boolean,
        val addresses: List<String>,
    ) {
        /** 旧实现 [GeetestCompanionService.resolveLocalIpAddress] 的枚举分支会取到的那个地址。 */
        val legacyPickedAddress: String?
            get() = addresses.firstOrNull()

        /** 带完整 flags 的可读行，便于肉眼比对 isUp 的两个 flag 哪个没置位。 */
        fun format(): String {
            val flags = buildString {
                append("idx=").append(index)
                append(" up=").append(isUp)
                append(" (RUNNING=").append(isRunning).append(')')
                append(" loop=").append(isLoopback)
                append(" mcast=").append(supportsMulticast)
            }
            val addr = if (addresses.isEmpty()) "<none>" else addresses.joinToString(",")
            return "$name  $flags  →  $addr"
        }
    }

    /**
     * 读 `NetworkInterface` 的底层 flags。
     *
     * `isUp()` 要求 `IFF_UP | IFF_RUNNING` **同时**置位，所以光看 isUp 无法区分
     * "没插线的 eth0 有 IFF_UP 但没 IFF_RUNNING"（旧实现会误取它上面的陈旧地址）
     * 和"接口彻底 down"这两种情况。libcore 把 flags 藏在私有 `getFlags()` 里，
     * Android SDK stub 也不暴露，只能反射；拿不到就返回 -1。
     */
    private fun reflectInterfaceFlags(ni: NetworkInterface): Int = runCatching {
        val method = ni.javaClass.getDeclaredMethod("getFlags")
        method.isAccessible = true
        (method.invoke(ni) as? Number)?.toInt() ?: -1
    }.getOrDefault(-1)

    /**
     * `NetworkInterface.getNetworkInterfaces()` 的原始顺序。
     *
     * **这个顺序没有语义** —— 保留它原样返回就是为了让 HUD 如实展示"旧实现会取到谁"。
     * AOSP `getAll()` 把 getifaddrs 的结果塞进 `HashMap` 再 values() 出去，
     * 桶顺序还取决于当前网卡数量，多一个 tun0/vlan0 其它网卡的相对顺序都可能变。
     */
    fun enumerateInterfaces(): List<InterfaceReport> =
        runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull()
            ?.toList().orEmpty()
            .map { ni ->
                InterfaceReport(
                    name = runCatching { ni.name }.getOrDefault("?"),
                    index = runCatching { ni.index }.getOrDefault(-1),
                    isUp = runCatching { ni.isUp }.getOrDefault(false),
                    isLoopback = runCatching { ni.isLoopback }.getOrDefault(true),
                    isRunning = reflectInterfaceFlags(ni) and IFF_RUNNING != 0,
                    supportsMulticast = runCatching { ni.supportsMulticast() }.getOrDefault(false),
                    addresses = runCatching { ni.inetAddresses.toList() }.getOrDefault(emptyList())
                        .mapNotNull { addr ->
                            val text = runCatching { addr.hostAddress }.getOrNull() ?: return@mapNotNull null
                            // 区分 IPv4/IPv6 与 loopback/link-local，否则一屏 IPv6 看不出问题在哪
                            val kind = when {
                                addr is Inet4Address -> "v4"
                                addr is java.net.Inet6Address -> "v6"
                                else -> "?"
                            }
                            val scope = when {
                                runCatching { addr.isLoopbackAddress }.getOrDefault(false) -> "loop"
                                runCatching { addr.isLinkLocalAddress }.getOrDefault(false) -> "link"
                                runCatching { addr.isSiteLocalAddress }.getOrDefault(false) -> "site"
                                else -> "pub"
                            }
                            "$text [$kind/$scope]"
                        },
                )
            }

    /**
     * `ConnectivityManager.getLinkProperties()` 视角：系统认为在用的网络 + 它的接口名 + 本机地址。
     * 这是官方文档给出的取本机 IP 的路径。
     */
    fun activeNetworkReport(context: Context): List<String> {
        val cm = runCatching {
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        }.getOrNull() ?: return listOf("<ConnectivityManager unavailable>")

        val networks = LinkedHashSet<Network>()
        runCatching { cm.activeNetwork }.getOrNull()?.let(networks::add)
        runCatching { cm.allNetworks }.getOrNull()?.let(networks::addAll)
        if (networks.isEmpty()) return listOf("<no active/all network>")

        return networks.map { n ->
            val lp = runCatching { cm.getLinkProperties(n) }.getOrNull()
            val caps = runCatching { cm.getNetworkCapabilities(n) }.getOrNull()
            val transports = buildList {
                if (caps == null) add("caps=null") else {
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ETHERNET")
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("WIFI")
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("CELLULAR")
                }
            }
            val isDefault = runCatching { n == cm.activeNetwork }.getOrDefault(false)
            val iface = lp?.interfaceName ?: "<null>"
            val addrs = lp?.linkAddresses.orEmpty().map { la: LinkAddress ->
                val text = runCatching { la.address.hostAddress }.getOrNull() ?: "?"
                val scope = when {
                    runCatching { la.address.isLinkLocalAddress }.getOrDefault(false) -> "link"
                    la.prefixLength > 0 -> "pfx${la.prefixLength}"
                    else -> "?"
                }
                "$text/$scope"
            }
            "net=$n${if (isDefault) " (active/default)" else ""} " +
                "iface=$iface [${transports.joinToString("+").ifEmpty { "none" }}] " +
                "addrs=${addrs.joinToString(",").ifEmpty { "<none>" }}"
        }
    }

    /** 旧实现实际会用的 host，以及它是否等于任何一个非回环 IPv4。 */
    fun legacyHostReport(context: Context): List<String> {
        val legacy = runCatching { GeetestCompanionService.resolveLocalIpAddress(context) }
            .getOrElse { return listOf("resolveLocalIpAddress threw: ${it.javaClass.simpleName}: ${it.message}") }

        val v4s = enumerateInterfaces()
            .flatMap { it.addresses }
            .map { it.substringBefore(' ') }
            .filter { text -> text.count { it == '.' } == 3 && !text.startsWith("127.") }

        return buildList {
            add("legacy host = '${legacy.ifBlank { "<blank>" }}'")
            add("legacy == 0.0.0.0 ? ${legacy == "0.0.0.0"}")
            add("legacy blank ? ${legacy.isBlank()}")
            if (legacy.isNotBlank() && legacy != "0.0.0.0") {
                add("legacy matches a live IPv4 ? ${v4s.contains(legacy)}")
                add("live IPv4s = ${v4s.joinToString(",").ifEmpty { "<none>" }}")
            }
        }
    }

    /** 屏幕/dp 上下文 —— 问题一的第二条裁切路径（弹窗总高 vs 屏高）要靠它。 */
    fun screenReport(context: Context): List<String> {
        val dm = context.resources.displayMetrics
        val cfg = context.resources.configuration
        return buildList {
            add("density = ${dm.density} (${dm.densityDpi}dpi)")
            add("screen px = ${dm.widthPixels}x${dm.heightPixels}")
            add("screen dp = ${cfg.screenWidthDp}x${cfg.screenHeightDp}")
            add("smallestScreenWidthDp = ${cfg.smallestScreenWidthDp}")
            add("uiMode TV? ${(cfg.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION}")
            add("fontScale = ${cfg.fontScale}")
        }
    }

    /** 一次性拼出 HUD 需要的全部行。 */
    fun collect(context: Context): List<String> = buildList {
        add("── screen ──")
        addAll(screenReport(context))
        add("")
        add("── NetworkInterface (raw getNetworkInterfaces() order) ──")
        val ifaces = enumerateInterfaces()
        if (ifaces.isEmpty()) add("<empty>") else ifaces.forEach { add(it.format()) }
        add("legacy branch would pick: ${ifaces.firstNotNullOfOrNull { it.legacyPickedAddress } ?: "<null>"}")
        add("")
        add("── ConnectivityManager / LinkProperties ──")
        addAll(activeNetworkReport(context))
        add("")
        add("── legacy resolveLocalIpAddress ──")
        addAll(legacyHostReport(context))
    }
}
