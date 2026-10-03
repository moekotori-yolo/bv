package dev.aaa1115910.bv.tv.component

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 这些用例把"面板装不下视口"和"弹窗装不下屏幕"两条裁切路径的判定固化下来。
 *
 * 真实尺寸来自 GeeTest v3 运行时实测（1400x1000 视口）：
 * - slide 面板 panel_box border-box 280x287
 * - click 面板 panel_box 320x410
 * - v3 不变量 height = width x 1.02518
 */
class GeetestTvDebugHudTest {

    private fun payload(
        innerWidth: Int = 504,
        innerHeight: Int = 320,
        dpr: String = "1.0",
        pageScale: String = "1.0",
        boxW: String = "320",
        boxH: String = "410",
        boxClass: String = "geetest_panel_box geetest_panelshowclick",
        boxTop: String = "-45",
        boxSizing: String = "content-box",
        slider: String = "0",
        commit: String = "1",
        head: String = "1",
    ) = "$innerWidth|$innerHeight|$dpr|$pageScale|$boxW|$boxH|$boxClass|$boxTop|$boxSizing|$slider|$commit|$head"

    @Test
    fun parsesFullProbePayload() {
        val metrics = parseGeetestPanelMetrics(payload())
        assertNotNull(metrics)
        assertEquals(504, metrics.innerWidth)
        assertEquals(320, metrics.innerHeight)
        assertEquals(1.0f, metrics.devicePixelRatio)
        assertEquals(320f, metrics.panelBoxWidth)
        assertEquals(410f, metrics.panelBoxHeight)
        assertEquals(-45f, metrics.panelBoxTop)
        assertEquals("content-box", metrics.panelBoxBoxSizing)
        assertTrue(metrics.hasCommitButton)
        assertTrue(metrics.hasHead)
        assertFalse(metrics.hasSliderButton)
    }

    @Test
    fun rejectsMalformedPayloads() {
        assertNull(parseGeetestPanelMetrics(null))
        assertNull(parseGeetestPanelMetrics(""))
        assertNull(parseGeetestPanelMetrics("   "))
        assertNull(parseGeetestPanelMetrics("1|2|3"))
        // 字段数够但值非法
        assertNull(parseGeetestPanelMetrics("a|b|c|d|e|f|g|h|i|j|k|l|m|"))
    }

    @Test
    fun clickPanelIn320pxViewportIsClippedBothSides() {
        // click 面板 320x410 居中在 320px 视口：top = 320/2 - 410/2 = -45
        val metrics = assertNotNull(parseGeetestPanelMetrics(payload()))
        assertTrue(metrics.isClipped)
        assertEquals(45f, metrics.topClippedPx)
        assertEquals(45f, metrics.bottomClippedPx)
        assertEquals(90f, metrics.totalClippedPx)
    }

    @Test
    fun slidePanelFitsIn320pxViewport() {
        // slide 面板 280x287 居中在 320px 视口：top = 160 - 143.5 = 16.5
        val metrics = assertNotNull(
            parseGeetestPanelMetrics(
                payload(boxW = "280", boxH = "287", boxTop = "16.5", slider = "1", commit = "0", head = "0")
            )
        )
        assertFalse(metrics.isClipped)
        assertEquals(0f, metrics.totalClippedPx)
    }

    @Test
    fun onlyBottomClippedWhenPanelHangsBelowViewport() {
        val metrics = assertNotNull(parseGeetestPanelMetrics(payload(boxTop = "0")))
        assertFalse(metrics.topClippedPx > 0f)
        assertEquals(90f, metrics.bottomClippedPx)
        assertTrue(metrics.isClipped)
    }

    @Test
    fun aspectRatioMatchesEachPanelTypeBaseline() {
        // 1.02518 只在 slide 下成立（278→285 / 300→307.554 / 348→356.763 三组实测吻合），
        // 不能当成通用不变量 —— click 是 320x410，比值 1.28125 完全不同。
        val slide = assertNotNull(
            parseGeetestPanelMetrics(
                payload(boxW = "280", boxH = "287", boxTop = "16.5", boxClass = "geetest_panel_box geetest_panelshowslide")
            )
        )
        assertEquals(1.02518f, slide.aspectRatio, absoluteTolerance = 0.001f)

        val click = assertNotNull(parseGeetestPanelMetrics(payload()))
        assertEquals(1.28125f, click.aspectRatio, absoluteTolerance = 0.001f)
    }

    @Test
    fun aspectRatioDeviationDetectsBrokenLayout() {
        // 宿主页面的 * { box-sizing } 之类覆盖改坏内部布局时，比值会同时偏离该类型的基线。
        val broken = assertNotNull(
            parseGeetestPanelMetrics(
                payload(boxW = "320", boxH = "200", boxClass = "geetest_panel_box geetest_panelshowslide")
            )
        )
        assertEquals(0.625f, broken.aspectRatio, absoluteTolerance = 0.001f)
    }

    @Test
    fun hudFlagsWebViewViewportClipping() {
        val metrics = assertNotNull(parseGeetestPanelMetrics(payload()))
        val lines = formatGeetestPanelMetrics(
            metrics = metrics,
            webViewWidthPx = 1008,
            webViewHeightPx = 640,
            density = 2f,
            dialogHeightDp = 520,
            screenHeightDp = 1080,
        )
        val text = lines.joinToString("\n")
        assertTrue(text.contains("CLIPPED"), "应报出裁切，实际:\n$text")
        assertTrue(text.contains("required height = 410"), "应报出所需高度，实际:\n$text")
    }

    @Test
    fun hudFlagsDialogOverflowingScreenSeparately() {
        val metrics = assertNotNull(parseGeetestPanelMetrics(payload()))
        val lines = formatGeetestPanelMetrics(
            metrics = metrics,
            webViewWidthPx = 1008,
            webViewHeightPx = 640,
            density = 2f,
            dialogHeightDp = 520,
            screenHeightDp = 360,
        )
        val text = lines.joinToString("\n")
        assertTrue(text.contains("OVERFLOW"), "弹窗溢出会裁掉 WebView 下半部分，实际:\n$text")
        assertTrue(text.contains("160 dp"), "居中对齐时上下各切一半，实际:\n$text")
    }

    @Test
    fun hudReportsWaitingWhenMetricsAbsent() {
        val lines = formatGeetestPanelMetrics(
            metrics = null,
            webViewWidthPx = 0,
            webViewHeightPx = 0,
            density = 1f,
            dialogHeightDp = 0,
            screenHeightDp = 0,
        )
        assertEquals(1, lines.size)
        assertTrue(lines.single().contains("waiting"))
    }

    @Test
    fun malformedFieldCountIsRejected() {
        assertNull(parseGeetestPanelMetrics("1|2|3"))
        assertNotNull(parseGeetestPanelMetrics(payload()), "完整 12 字段必须能解析")
        assertNull(
            parseGeetestPanelMetrics(payload().dropLast(2)),
            "缺字段必须拒绝而不是静默读出脏数据",
        )
    }

    @Test
    fun probePayloadFieldCountMatchesParserContract() {
        // JS 探针的 payload 数组长度必须与解析器的字段数一致，否则 HUD 永远收不到数据。
        val js = buildGeetestDebugProbeJs()
        val arrayBody = js.substringAfter("var payload = [").substringBefore("].join('|')")
        val jsFieldCount = arrayBody.lines()
            .count { it.trim().startsWith("Math.round") || it.trim().startsWith("num(") ||
                it.trim().startsWith("(box") || it.trim().startsWith("rect ?") ||
                it.trim().startsWith("(cs &&") || it.trim().startsWith("slider ?") ||
                it.trim().startsWith("commit ?") || it.trim().startsWith("head ?") }
        assertEquals(12, jsFieldCount, "JS 探针字段数:\n$arrayBody")
        assertNotNull(parseGeetestPanelMetrics(payload()))
    }

    @Test
    fun probeTargetsPanelBoxNotTheFullScreenMask() {
        val js = buildGeetestDebugProbeJs()
        // document.querySelector('.geetest_panel') 会命中 position:fixed 的全视口遮罩，
        // 而且面板内还有同名 .geetest_panel（底部操作条），量出来永远是视口尺寸。
        assertFalse(
            js.contains("document.querySelector('.geetest_panel')"),
            "探针不能用 document.querySelector('.geetest_panel')",
        )
        assertTrue(js.contains("document.querySelector('.geetest_panel_box')"))
        // v3 真实 class 是 geetest_slider_button（多个 r），不是 geetest_slide_button
        assertTrue(js.contains("geetest_slider_button"))
        assertFalse(js.contains("geetest_slide_button'"))
        // 探针必须把 timer 句柄挂到 window 上，dispose 才能 clearInterval 它；
        // 句柄的清理动作本身在 Kotlin 侧，不在探针里。
        assertTrue(js.contains("__bvGeetestDebugTimer"))
    }
}
