package dev.aaa1115910.bv.tv.component

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.aaa1115910.bv.network.LanIpDiagnostics

/**
 * Geetest 面板实测指标。
 *
 * 数据来自页面内的 JS 探针（见 [buildGeetestDebugProbeJs]），用 `|` 分隔传输而不是 JSON，
 * 这样 tv 模块不需要任何序列化依赖，解析逻辑也能直接写单测。
 */
data class GeetestPanelMetrics(
    val innerWidth: Int,
    val innerHeight: Int,
    val devicePixelRatio: Float,
    val pageScale: Float,
    val panelBoxWidth: Float,
    val panelBoxHeight: Float,
    val panelBoxClass: String,
    val panelBoxTop: Float,
    /** `.geetest_panel_box` 的 computed box-sizing，用来验证宿主页面的 `* { box-sizing }` 有没有干扰 gt.js。 */
    val panelBoxBoxSizing: String,
    val hasSliderButton: Boolean,
    val hasCommitButton: Boolean,
    val hasHead: Boolean,
) {
    /** 视口顶部被裁掉的像素。 */
    val topClippedPx: Float get() = (-panelBoxTop).coerceAtLeast(0f)

    /** 视口底部被裁掉的像素。 */
    val bottomClippedPx: Float
        get() = (panelBoxTop + panelBoxHeight - innerHeight).coerceAtLeast(0f)

    val isClipped: Boolean get() = topClippedPx > 0.5f || bottomClippedPx > 0.5f

    val totalClippedPx: Float get() = topClippedPx + bottomClippedPx

    /**
     * 各面板类型的高宽比基线（v3 实测）：
     * - slide `geetest_panelshowslide`：278x285 / 300x307.554 / 348x356.763 → 恒为 1.02518
     * - click `geetest_panelshowclick`：320x410 → 1.28125
     *
     * **不是通用不变量** —— 按当前类型比对，明显偏离说明宿主页面的 CSS
     * （`* { box-sizing }` 之类）改坏了 gt.js 的内部百分比布局。
     */
    val aspectRatio: Float
        get() = if (panelBoxWidth > 0f) panelBoxHeight / panelBoxWidth else 0f

    val panelType: String
        get() = when {
            panelBoxClass.contains("panelshowslide") -> "slide"
            panelBoxClass.contains("panelshowclick") -> "click"
            panelBoxClass.contains("panelshowbeeline") -> "beeline"
            panelBoxWidth > 0f -> "loading/other"
            else -> "<none>"
        }

    /** 该类型的理论高宽比；未知类型返回 null，HUD 不做判断。 */
    val expectedAspectRatio: Float?
        get() = when (panelType) {
            "slide" -> 1.02518f
            "click" -> 1.28125f
            else -> null
        }

    /** 面板渲染出来的绝对高度，用来决定 WebView 该给多高。 */
    val requiredHeightCssPx: Float get() = panelBoxHeight

    /** gt.js 把拖动位移写成未缩放的 client 像素差，所以视觉缩放比 = 1 / (dpr × pageScale 之外的项)。 */
    val cssPxToViewPx: Float get() = devicePixelRatio * pageScale
}

/** 探针上报的字段数，与 [buildGeetestDebugProbeJs] 里的 payload 数组严格对应。 */
private const val METRICS_FIELD_COUNT = 12

/**
 * 解析探针上报的 `|` 分隔字符串。
 *
 * 用 `runCatching` + 宽松解析：探针是纯诊断，任何字段异常都不该让调试 HUD 崩溃。
 */
internal fun parseGeetestPanelMetrics(raw: String?): GeetestPanelMetrics? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return null
    val parts = text.split('|')
    if (parts.size < METRICS_FIELD_COUNT) return null
    return runCatching {
        GeetestPanelMetrics(
            innerWidth = parts[0].toInt(),
            innerHeight = parts[1].toInt(),
            devicePixelRatio = parts[2].toFloat(),
            pageScale = parts[3].toFloat(),
            panelBoxWidth = parts[4].toFloat(),
            panelBoxHeight = parts[5].toFloat(),
            panelBoxClass = parts[6],
            panelBoxTop = parts[7].toFloat(),
            panelBoxBoxSizing = parts[8],
            hasSliderButton = parts[9] == "1",
            hasCommitButton = parts[10] == "1",
            hasHead = parts[11] == "1",
        )
    }.getOrNull()
}

/**
 * 弹窗内除 WebView 外的固定开销（标题、模式 Tab、状态文案、内边距）。
 * 用于把屏幕高度换算成 WebView 可用高度。
 */
private const val GEETEST_CHROME_RESERVE_DP = 200

/** 视口高度下限：滑块面板 285px、旧的 320dp 行为，两者都要容得下。 */
private const val GEETEST_MIN_VIEWPORT_DP = 320

/** 视口高度上限：超过这个值面板也不会更大，只是浪费屏幕。 */
private const val GEETEST_MAX_VIEWPORT_DP = 620

/**
 * 计算 WebView 的视口高度。
 *
 * 根因：原先硬编码 320dp，而 GeeTest v3 点选面板实测 320x410 CSS px，
 * 居中在 320px 视口里上下各被裁掉约 45px —— 顶部提示文字与底部「确定」
 * 按钮恰好落在被裁掉的区间里。
 *
 * @param screenHeightDp 屏幕高度（dp）
 * @param measuredPanelHeightCssPx 页面内探针实测的面板高度。拿到就直接按它给足，
 *        不再靠猜；v3 面板高宽比固定（slide 1.02518 / click 1.28125），
 *        知道高度即等于知道尺寸。探针仅在 debugHud 开启时上报，
 *        正式流程下走屏高估算这一分支。
 */
internal fun computeGeetestViewportDp(
    screenHeightDp: Int,
    measuredPanelHeightCssPx: Float = 0f,
): Int {
    // 面板高度是 CSS px；useWideViewPort=false 时 1 CSS px == 1 dp
    // （HUD 的 cssPxToViewPx 已验证），因此可直接与 dp 余量比较。
    val needed = if (measuredPanelHeightCssPx > 0f) {
        (measuredPanelHeightCssPx + 24f).toInt()
    } else {
        screenHeightDp - GEETEST_CHROME_RESERVE_DP
    }
    return needed.coerceIn(GEETEST_MIN_VIEWPORT_DP, GEETEST_MAX_VIEWPORT_DP)
}

/**
 * 把指标渲染成 HUD 的多行文本。抽成纯函数是为了能直接断言裁切判定逻辑。
 */
internal fun formatGeetestPanelMetrics(
    metrics: GeetestPanelMetrics?,
    webViewWidthPx: Int,
    webViewHeightPx: Int,
    density: Float,
    dialogHeightDp: Int,
    screenHeightDp: Int,
): List<String> = buildList {
    if (metrics == null) {
        add("panel metrics: <waiting for JS probe…>")
        return@buildList
    }

    add("viewport(css px) = ${metrics.innerWidth} x ${metrics.innerHeight}")
    add("devicePixelRatio = ${metrics.devicePixelRatio}   pageScale = ${metrics.pageScale}")
    add("cssPx→viewPx = ${"%.3f".format(metrics.cssPxToViewPx)}   webView px = $webViewWidthPx x $webViewHeightPx")
    add("")

    add("panel_box class = '${metrics.panelBoxClass}'")
    add("panel type = ${metrics.panelType}")
    add("panel_box size(css px) = ${metrics.panelBoxWidth} x ${metrics.panelBoxHeight}")
    add("panel_box top = ${"%.1f".format(metrics.panelBoxTop)}   box-sizing = ${metrics.panelBoxBoxSizing}")
    val expected = metrics.expectedAspectRatio
    if (expected == null) {
        add("ratio h/w = ${"%.5f".format(metrics.aspectRatio)}   (该类型无基线)")
    } else {
        val deviated = kotlin.math.abs(metrics.aspectRatio - expected) > 0.01f
        add(
            "ratio h/w = ${"%.5f".format(metrics.aspectRatio)}   " +
                "${metrics.panelType} 基线 ${"%.5f".format(expected)}" +
                if (deviated) "  ▶ 偏离，疑似被宿主页 CSS 改坏布局" else "  ✓"
        )
    }
    add("")

    add("presence: slider=${metrics.hasSliderButton} commit=${metrics.hasCommitButton} head=${metrics.hasHead}")
    add("")

    // 裁切路径 1：WebView 视口高度装不下面板
    add("── 裁切路径 1：WebView 视口 ──")
    add("required height = ${"%.0f".format(metrics.requiredHeightCssPx)} css px")
    add("viewport height  = ${metrics.innerHeight} css px")
    add("top clipped    = ${"%.1f".format(metrics.topClippedPx)} px")
    add("bottom clipped = ${"%.1f".format(metrics.bottomClippedPx)} px")
    add(
        if (metrics.isClipped) {
            "▶ CLIPPED, 合计损失 ${"%.1f".format(metrics.totalClippedPx)} px"
        } else {
            "▶ 视口内完整显示"
        }
    )
    add("")

    // 裁切路径 2：弹窗整体高度装不进屏幕
    add("── 裁切路径 2：弹窗 vs 屏幕 ──")
    add("dialog height = $dialogHeightDp dp   screen height = $screenHeightDp dp")
    add("density = $density")
    add(
        if (dialogHeightDp > screenHeightDp) {
            "▶ OVERFLOW, 溢出 ${dialogHeightDp - screenHeightDp} dp（居中对齐时上下各切 ${(dialogHeightDp - screenHeightDp) / 2} dp）"
        } else {
            "▶ 屏幕内放得下，余量 ${screenHeightDp - dialogHeightDp} dp"
        }
    )
}

/**
 * 注入到 Geetest 页面里的诊断探针。
 *
 * 关键点（全部来自 v3 运行时实测，踩过的坑都在注释里）：
 * - **不能**用 `document.querySelector('.geetest_panel')` 去找对话框：那是 `position:fixed`
 *   的全视口遮罩，而且面板内部还有一个同名的 `.geetest_panel`（底部操作条），
 *   `querySelector` 只会命中第一个（外层遮罩），量出来的 rect 完全是视口尺寸。
 * - 面板宽度/高度是**加 class 过渡**出来的（`transition: width .5s ease, height .5s ease`），
 *   首次 probe 可能量到 loading 态的 220x150，所以轮询要持续。
 * - `.geetest_slide_button` 在 v3 不存在，真实 class 是 `geetest_slider_button`。
 * - `position:fixed` 的包含块是视口，不受祖先 `overflow` 裁切，所以裁切只发生在 WebView 边界。
 */
internal fun buildGeetestDebugProbeJs(): String = """
(function () {
  function num(v) { return (Math.round((v || 0) * 100) / 100); }
  function report() {
    try {
      var viewportHeight = window.innerHeight || 0;
      // 对话框本体。geetest_panel_box 在文档里唯一，比从 panel 往下找更稳。
      var box = document.querySelector('.geetest_panel_box');
      var rect = box ? box.getBoundingClientRect() : null;
      var cs = box ? window.getComputedStyle(box) : null;
      var dpr = window.devicePixelRatio || 1;
      var vv = window.visualViewport;
      var pageScale = vv && vv.scale ? vv.scale : 1;
      var slider = document.querySelector('.geetest_slider_button');
      var commit = document.querySelector('.geetest_commit');
      var head = document.querySelector('.geetest_head');
      var payload = [
        Math.round(window.innerWidth || 0),
        Math.round(viewportHeight),
        num(dpr),
        num(pageScale),
        rect ? num(rect.width) : 0,
        rect ? num(rect.height) : 0,
        (box && box.className) ? String(box.className) : '',
        rect ? num(rect.top) : 0,
        (cs && cs.boxSizing) ? cs.boxSizing : '',
        slider ? '1' : '0',
        commit ? '1' : '0',
        head ? '1' : '0'
      ].join('|');
      if (window.Android && window.Android.onDebugMetrics) {
        window.Android.onDebugMetrics(payload);
      }
    } catch (e) { /* 诊断代码永不抛到页面 */ }
  }
  report();
  // 面板宽度是 0.5s 过渡出来的，持续轮询才能拿到稳定值。
  window.__bvGeetestDebugTimer = setInterval(report, 500);
})();
""".trimIndent()

/**
 * HUD 全局开关（仅 Debug 使用）。
 *
 * 放在 object 上而不是 Dialog 参数默认值里，是因为真实风控弹窗来自
 * `VideoPlayerV3Screen` 和 `SmsLoginContent` 等多处，设置页的开关必须能作用于它们全部。
 */
object GeetestTvDebugHudState {
    var enabled: Boolean by mutableStateOf(false)
}

/**
 * 调试 HUD：左半屏面板指标 + 右半屏局域网诊断。
 *
 * 只在 [debugHud] 为 true 时挂载，不 focusable / 不 clickable，因此不会抢遥控器焦点，
 * 也不会改变弹窗原有的按键分发。
 */
@Composable
internal fun GeetestTvDebugHud(
    metrics: GeetestPanelMetrics?,
    webViewWidthPx: Int,
    webViewHeightPx: Int,
    density: Float,
    dialogHeightDp: Int,
    screenHeightDp: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var lanLines by remember { mutableStateOf<List<String>>(emptyList()) }

    // 网卡枚举要跑 getifaddrs + 反射，代价不小，只在进入时取一次。
    LaunchedEffect(Unit) {
        lanLines = runCatching { LanIpDiagnostics.collect(context) }
            .getOrElse { listOf("<diagnostics failed: ${it.javaClass.simpleName}>") }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.82f))
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .horizontalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        formatGeetestPanelMetrics(
            metrics = metrics,
            webViewWidthPx = webViewWidthPx,
            webViewHeightPx = webViewHeightPx,
            density = density,
            dialogHeightDp = dialogHeightDp,
            screenHeightDp = screenHeightDp,
        ).forEach { line ->
            HudLine(line)
        }

        if (lanLines.isNotEmpty()) {
            HudLine("")
            lanLines.forEach { line -> HudLine(line) }
        }
    }
}

@Composable
private fun HudLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 13.sp,
        ),
        color = Color(0xFF9FE8A0),
    )
}
