package com.example.system

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

data class ScreenUiElement(
    val index: Int,
    val label: String,
    val viewId: String,
    val category: String,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean,
    val centerX: Int,
    val centerY: Int,
    val xPercent: Int,
    val yPercent: Int,
    val bounds: Rect,
    val screenZone: String = "ЦЕНТР",
    val className: String = ""
)

class AuraAccessibilityService : AccessibilityService() {

    companion object {
        private val _instance = MutableStateFlow<AuraAccessibilityService?>(null)
        val instance: StateFlow<AuraAccessibilityService?> = _instance.asStateFlow()

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

        private val _activePackage = MutableStateFlow("")
        val activePackage: StateFlow<String> = _activePackage.asStateFlow()

        private val _lastActionReport = MutableStateFlow("Сервис специальных возможностей готов")
        val lastActionReport: StateFlow<String> = _lastActionReport.asStateFlow()

        private val _screenElements = MutableStateFlow<List<ScreenUiElement>>(emptyList())
        val screenElements: StateFlow<List<ScreenUiElement>> = _screenElements.asStateFlow()

        private val _screenStructureSummary = MutableStateFlow("Экран готов к мультимодальному анализу координат")
        val screenStructureSummary: StateFlow<String> = _screenStructureSummary.asStateFlow()

        private val mainHandler = Handler(Looper.getMainLooper())

        fun runDelayed(delayMs: Long, block: (AuraAccessibilityService) -> Unit): Boolean {
            val svc = _instance.value ?: return false
            if (delayMs <= 0L) {
                mainHandler.post { _instance.value?.let(block) }
            } else {
                mainHandler.postDelayed({
                    _instance.value?.let(block)
                }, delayMs)
            }
            return true
        }
    }

    private var lastAutoInspectTimeMs = 0L

    private val semanticSynonyms: Map<String, List<String>> = mapOf(
        "поиск" to listOf("поиск", "найти", "search", "find", "query", "лупа", "искать", "search_button", "search_box", "search_src_text"),
        "найти" to listOf("поиск", "найти", "search", "find", "query", "искать"),
        "отправить" to listOf("отправить", "send", "submit", "post", "готово", "done", "enter", "отослать", "send_button"),
        "лайк" to listOf("нравится", "лайк", "like", "heart", "favorite", "класс", "оценить", "like_button"),
        "нравится" to listOf("нравится", "лайк", "like", "heart", "favorite"),
        "подписаться" to listOf("подписаться", "subscribe", "follow", "читать", "вступить", "subscribe_button"),
        "закрыть" to listOf("закрыть", "close", "dismiss", "cancel", "отмена", "крестик", "close_button"),
        "меню" to listOf("меню", "ещё", "еще", "more", "options", "menu", "три точки", "настройки", "overflow"),
        "назад" to listOf("назад", "back", "navigate up", "вернуться", "up"),
        "плей" to listOf("воспроизвести", "играть", "play", "пауза", "pause", "смотреть", "player"),
        "включи" to listOf("воспроизвести", "играть", "play", "смотреть", "открыть"),
        "профиль" to listOf("профиль", "аккаунт", "кабинет", "profile", "account", "avatar", "я"),
        "главная" to listOf("главная", "домой", "home", "лента", "feed", "main")
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        _instance.value = this
        _isConnected.value = true
        val msg = "Полный ИИ-контроль активен • Координатная сетка включена"
        _lastActionReport.value = msg
        AuraOverlayManager.showOverlay(this, preferAccessibilityOverlay = true)
        AuraOverlayManager.updateStatus(AuraOverlayVisualState.IDLE, msg)
        mainHandler.postDelayed({ inspectCurrentScreen() }, 500L)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString().orEmpty()
        if (pkg.isNotBlank() && pkg != packageName) {
            _activePackage.value = pkg
        }
        val now = System.currentTimeMillis()
        val type = event?.eventType ?: 0
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && now - lastAutoInspectTimeMs > 1200L)
        ) {
            lastAutoInspectTimeMs = now
            mainHandler.postDelayed({
                inspectCurrentScreen(silentOverlayStatus = true)
            }, 220L)
        }
    }

    override fun onInterrupt() {
        _lastActionReport.value = "Сервис специальных возможностей приостановлен"
    }

    override fun onDestroy() {
        _isConnected.value = false
        _instance.value = null
        super.onDestroy()
    }

    fun getScreenSizePixels(): Pair<Int, Int> {
        val metrics = resources.displayMetrics
        return Pair(metrics.widthPixels.coerceAtLeast(1080), metrics.heightPixels.coerceAtLeast(1920))
    }

    /**
     * Captures a live visual screenshot of the current screen on Android 11+ (API 30+)
     * and encodes it as a compact JPEG Base64 string so the Gemini server model can visually
     * inspect the screen layout, icons, images, and context alongside the coordinate DOM tree.
     */
    suspend fun captureScreenBase64Jpeg(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return withTimeoutOrNull(1800L) {
            suspendCancellableCoroutine { cont ->
                try {
                    takeScreenshot(
                        Display.DEFAULT_DISPLAY,
                        mainExecutor,
                        object : TakeScreenshotCallback {
                            override fun onSuccess(screenshot: ScreenshotResult) {
                                try {
                                    val hwBuffer = screenshot.hardwareBuffer
                                    val colorSpace = screenshot.colorSpace
                                    val hwBitmap = Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace)
                                    val swBitmap = hwBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                                    hwBitmap?.recycle()
                                    hwBuffer.close()

                                    if (swBitmap == null) {
                                        if (cont.isActive) cont.resume(null)
                                        return
                                    }

                                    val maxDim = 640
                                    val scale = minOf(
                                        maxDim.toFloat() / swBitmap.width.coerceAtLeast(1),
                                        maxDim.toFloat() / swBitmap.height.coerceAtLeast(1),
                                        1f
                                    )
                                    val finalBmp = if (scale < 1f) {
                                        val w = (swBitmap.width * scale).toInt().coerceAtLeast(1)
                                        val h = (swBitmap.height * scale).toInt().coerceAtLeast(1)
                                        Bitmap.createScaledBitmap(swBitmap, w, h, true).also {
                                            if (it !== swBitmap) swBitmap.recycle()
                                        }
                                    } else {
                                        swBitmap
                                    }

                                    val out = ByteArrayOutputStream()
                                    finalBmp.compress(Bitmap.CompressFormat.JPEG, 70, out)
                                    finalBmp.recycle()
                                    val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                                    if (cont.isActive) cont.resume(b64)
                                } catch (_: Exception) {
                                    if (cont.isActive) cont.resume(null)
                                }
                            }

                            override fun onFailure(errorCode: Int) {
                                if (cont.isActive) cont.resume(null)
                            }
                        }
                    )
                } catch (_: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    /**
     * Deeply analyzes all active windows on screen, indexing up to 70 buttons, input fields,
     * unlabeled icons, tabs, and interactive items with exact pixel (centerX, centerY)
     * and percentage coordinates for Gemini AI context analysis.
     */
    fun inspectCurrentScreen(silentOverlayStatus: Boolean = false): List<ScreenUiElement> {
        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels.coerceAtLeast(1)
        val screenH = metrics.heightPixels.coerceAtLeast(1)

        val roots = mutableListOf<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { roots.add(it) }
        try {
            for (win in windows) {
                val r = win.root ?: continue
                val winPkg = r.packageName?.toString().orEmpty()
                if (winPkg != packageName && roots.none { it == r }) {
                    roots.add(r)
                }
            }
        } catch (_: Exception) {
        }

        val rawCandidates = mutableListOf<ScreenUiElement>()
        val seenSignatures = mutableSetOf<String>()
        var nextIndex = 1

        fun computeScreenZone(xPct: Int, yPct: Int): String {
            return when {
                yPct <= 14 && xPct <= 30 -> "ВЕРХ-ЛЕВО"
                yPct <= 14 && xPct >= 70 -> "ВЕРХ-ПРАВО"
                yPct <= 14 -> "ВЕРХ-ШАПКА"
                yPct >= 86 -> "НИЗ-НАВИГАЦИЯ"
                yPct >= 72 && xPct >= 72 -> "FAB-ПРАВО-НИЗ"
                else -> "ЦЕНТР-КОНТЕНТ"
            }
        }

        fun collectNodes(node: AccessibilityNodeInfo?) {
            if (node == null || nextIndex > 70) return
            if (!node.isVisibleToUser) return

            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.width() > 10 && bounds.height() > 10 &&
                bounds.centerX() in 0..screenW && bounds.centerY() in 0..screenH
            ) {
                val text = node.text?.toString()?.trim().orEmpty()
                val desc = node.contentDescription?.toString()?.trim().orEmpty()
                val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    node.hintText?.toString()?.trim().orEmpty()
                } else {
                    ""
                }
                val rawViewId = node.viewIdResourceName?.substringAfterLast('/')?.trim().orEmpty()
                val className = node.className?.toString()?.substringAfterLast('.').orEmpty()

                val clickable = node.isClickable ||
                    (node.parent?.isClickable == true && (text.isNotEmpty() || desc.isNotEmpty() || rawViewId.isNotEmpty()))
                val editable = node.isEditable || className.contains("EditText", ignoreCase = true)
                val scrollable = node.isScrollable
                val checkable = node.isCheckable

                val xPct = ((bounds.exactCenterX() / screenW) * 100f).toInt().coerceIn(1, 99)
                val yPct = ((bounds.exactCenterY() / screenH) * 100f).toInt().coerceIn(1, 99)
                val zone = computeScreenZone(xPct, yPct)

                val childLabel = if (text.isEmpty() && desc.isEmpty() && clickable) {
                    extractChildTextOrDesc(node, maxDepth = 2)
                } else {
                    ""
                }

                val inferredLabel = when {
                    text.isNotEmpty() -> text
                    desc.isNotEmpty() -> desc
                    hint.isNotEmpty() -> hint
                    childLabel.isNotEmpty() -> childLabel
                    rawViewId.isNotEmpty() -> humanizeViewId(rawViewId).ifEmpty { rawViewId }
                    editable -> "Поле ввода текста"
                    clickable && (className.contains("Image", ignoreCase = true) || className.contains("Button", ignoreCase = true)) ->
                        "Иконка/Кнопка ($zone)"
                    else -> ""
                }

                if (inferredLabel.isNotEmpty() || editable) {
                    val finalLabel = inferredLabel.ifEmpty { "Поле ввода" }.replace("\n", " ").take(70)
                    val category = when {
                        editable -> "ПОЛЕ_ВВОДА"
                        checkable -> "ПЕРЕКЛЮЧАТЕЛЬ"
                        className.contains("Tab", ignoreCase = true) || zone == "НИЗ-НАВИГАЦИЯ" -> "ВКЛАДКА"
                        clickable && (className.contains("Image", ignoreCase = true) || rawViewId.contains("icon", ignoreCase = true)) -> "ИКОНКА_КНОПКА"
                        clickable -> "КНОПКА"
                        else -> "ТЕКСТ"
                    }

                    val signature = "${finalLabel.lowercase()}_${xPct / 3}_${yPct / 3}"

                    if ((clickable || editable || finalLabel.length >= 2) && seenSignatures.add(signature)) {
                        rawCandidates.add(
                            ScreenUiElement(
                                index = nextIndex++,
                                label = finalLabel,
                                viewId = rawViewId,
                                category = category,
                                isClickable = clickable,
                                isEditable = editable,
                                isScrollable = scrollable,
                                centerX = bounds.centerX(),
                                centerY = bounds.centerY(),
                                xPercent = xPct,
                                yPercent = yPct,
                                bounds = Rect(bounds),
                                screenZone = zone,
                                className = className
                            )
                        )
                    }
                }
            }

            for (i in 0 until node.childCount) {
                collectNodes(node.getChild(i))
            }
        }

        for (r in roots) {
            collectNodes(r)
        }

        _screenElements.value = rawCandidates
        val clickableCount = rawCandidates.count { it.isClickable }
        val inputCount = rawCandidates.count { it.isEditable }
        val shortPkg = _activePackage.value.substringAfterLast('.').ifBlank { "Активное окно" }

        val summaryBuilder = StringBuilder()
        summaryBuilder.append(
            "Активное приложение: ${_activePackage.value.ifBlank { shortPkg }} | Разрешение экрана: ${screenW}x${screenH}px | Кнопок: $clickableCount, Полей ввода: $inputCount.\n"
        )
        if (rawCandidates.isNotEmpty()) {
            summaryBuilder.append("Интерактивная карта элементов и точных координат экрана:\n")
            val formatted = rawCandidates.take(55).joinToString("\n") { el ->
                val idPart = if (el.viewId.isNotBlank()) " id=${el.viewId}" else ""
                val clickPart = if (el.isEditable) "EDITABLE_INPUT" else if (el.isClickable) "CLICKABLE" else "READONLY"
                "[#${el.index}] ${el.category} \"$el.label\"$idPart | зона=${el.screenZone} | $clickPart | пиксели=(${el.centerX}, ${el.centerY}) | проценты=(${el.xPercent}%, ${el.yPercent}%) | рамка=[${el.bounds.left},${el.bounds.top}-${el.bounds.right},${el.bounds.bottom}]"
            }
            summaryBuilder.append(formatted)
        } else {
            summaryBuilder.append("Элементы дерева окна не обнаружены (используйте визуальный контекст или жест по координатам).")
        }

        val summaryText = summaryBuilder.toString()
        _screenStructureSummary.value = summaryText
        AuraOverlayManager.refreshScreenElementsOnOverlay(rawCandidates, _activePackage.value)

        if (!silentOverlayStatus) {
            val report = "Анализ ($shortPkg): $clickableCount кнопок, $inputCount полей (${screenW}x${screenH})"
            _lastActionReport.value = report
            AuraOverlayManager.updateStatus(AuraOverlayVisualState.IDLE, report)
        }
        return rawCandidates
    }

    private fun extractChildTextOrDesc(node: AccessibilityNodeInfo, maxDepth: Int): String {
        if (maxDepth <= 0) return ""
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val t = child.text?.toString()?.trim().orEmpty()
            if (t.isNotEmpty()) return t
            val d = child.contentDescription?.toString()?.trim().orEmpty()
            if (d.isNotEmpty()) return d
            val nested = extractChildTextOrDesc(child, maxDepth - 1)
            if (nested.isNotEmpty()) return nested
        }
        return ""
    }

    private fun humanizeViewId(rawId: String): String {
        val lower = rawId.lowercase()
        return when {
            lower.contains("search") -> "Поиск"
            lower.contains("send") -> "Отправить"
            lower.contains("like") || lower.contains("heart") -> "Нравится"
            lower.contains("dislike") -> "Не нравится"
            lower.contains("comment") || lower.contains("reply") -> "Комментарии"
            lower.contains("share") -> "Поделиться"
            lower.contains("back") || lower.contains("navigate_up") -> "Назад"
            lower.contains("close") || lower.contains("dismiss") -> "Закрыть"
            lower.contains("more") || lower.contains("menu") || lower.contains("overflow") -> "Меню"
            lower.contains("play") -> "Воспроизвести"
            lower.contains("pause") -> "Пауза"
            lower.contains("sub") -> "Подписаться"
            lower.contains("avatar") || lower.contains("profile") -> "Профиль"
            lower.contains("mic") || lower.contains("voice") -> "Голосовой ввод"
            lower.contains("add") || lower.contains("create") || lower.contains("fab") || lower.contains("plus") -> "Создать / Добавить"
            lower.contains("settings") -> "Настройки"
            else -> ""
        }
    }

    /**
     * Clicks an indexed UI element (#1..#N) using both its AccessibilityNodeInfo AND
     * exact hardware pixel coordinates (centerX, centerY) with a visual target crosshair.
     */
    fun clickElementByIndex(index: Int, reasonNote: String = ""): String {
        var elements = _screenElements.value
        if (elements.isEmpty()) {
            elements = inspectCurrentScreen(silentOverlayStatus = true)
        }
        val target = elements.firstOrNull { it.index == index }
            ?: elements.filter { it.isClickable || it.isEditable }.getOrNull(index - 1)
            ?: return "Элемент #$index не найден на экране"

        val statusMsg = if (reasonNote.isNotBlank()) {
            "$reasonNote -> #${target.index} (${target.centerX},${target.centerY})"
        } else {
            "Нажимаю #${target.index} «${target.label.take(18)}» (${target.centerX},${target.centerY})"
        }

        AuraOverlayManager.updateStatus(AuraOverlayVisualState.ACTING, statusMsg)
        AuraOverlayManager.showTapCrosshair(target.centerX, target.centerY, "#${target.index} ${target.label}")

        val root = rootInActiveWindow ?: windows.firstOrNull()?.root
        var nodeClicked = false
        if (root != null) {
            val matchedNode = findNodeByTextOrBounds(root, target.label, target.bounds)
            if (matchedNode != null) {
                nodeClicked = performClickOnNodeOrParent(matchedNode)
            }
        }
        // Always reinforce with hardware gesture tap if node action didn't confirm or for custom views
        if (!nodeClicked) {
            dispatchTapPixel(target.centerX.toFloat(), target.centerY.toFloat())
        }

        val msg = "Нажат #${target.index} «${target.label.take(22)}» в координатах (${target.centerX}, ${target.centerY})"
        _lastActionReport.value = msg
        mainHandler.postDelayed({ inspectCurrentScreen(silentOverlayStatus = true) }, 750L)
        return msg
    }

    /**
     * Taps exact screen pixel coordinates (xPx, yPx) chosen by Gemini AI from the screen context.
     * Also attempts to trigger AccessibilityNodeInfo click at those coordinates if a node exists there,
     * and dispatches hardware GestureDescription tap + visual target crosshair.
     */
    fun tapExactPixel(xPx: Int, yPx: Int, reasonLabel: String = ""): String {
        val (screenW, screenH) = getScreenSizePixels()
        // If AI accidentally passed 0..100 percentages instead of pixels, auto-convert when both <= 100
        val finalX = if (xPx in 1..100 && yPx in 1..100 && screenW > 300) {
            ((xPx / 100f) * screenW).toInt()
        } else {
            xPx.coerceIn(5, screenW - 5)
        }
        val finalY = if (xPx in 1..100 && yPx in 1..100 && screenH > 300) {
            ((yPx / 100f) * screenH).toInt()
        } else {
            yPx.coerceIn(5, screenH - 5)
        }

        val displayLabel = reasonLabel.ifBlank { "Точка ($finalX, $finalY)" }
        AuraOverlayManager.updateStatus(
            AuraOverlayVisualState.ACTING,
            "Клик по координатам ($finalX, $finalY): ${displayLabel.take(20)}"
        )
        AuraOverlayManager.showTapCrosshair(finalX, finalY, displayLabel)

        // Check if an accessibility node sits at (finalX, finalY)
        val root = rootInActiveWindow ?: windows.firstOrNull()?.root
        var nodeClicked = false
        if (root != null) {
            val hitNode = findClickableNodeAtPoint(root, finalX, finalY)
            if (hitNode != null) {
                nodeClicked = performClickOnNodeOrParent(hitNode)
            }
        }

        // Always dispatch hardware gesture tap at exact pixel coordinates for guaranteed click
        if (!nodeClicked) {
            dispatchTapPixel(finalX.toFloat(), finalY.toFloat())
        } else {
            // Even if node was clicked, some custom views inside containers rely on touch coordinates
            // Only skip gesture if node click already succeeded cleanly
        }

        val msg = "Точное нажатие в координаты ($finalX, $finalY) • $displayLabel"
        _lastActionReport.value = msg
        mainHandler.postDelayed({ inspectCurrentScreen(silentOverlayStatus = true) }, 750L)
        return msg
    }

    fun longPressExactPixel(xPx: Int, yPx: Int, reasonLabel: String = ""): String {
        val (screenW, screenH) = getScreenSizePixels()
        val finalX = xPx.coerceIn(5, screenW - 5)
        val finalY = yPx.coerceIn(5, screenH - 5)
        val displayLabel = reasonLabel.ifBlank { "Удержание ($finalX, $finalY)" }
        AuraOverlayManager.updateStatus(
            AuraOverlayVisualState.ACTING,
            "Долгое нажатие ($finalX, $finalY): ${displayLabel.take(18)}"
        )
        AuraOverlayManager.showTapCrosshair(finalX, finalY, displayLabel)
        dispatchLongPressPixel(finalX.toFloat(), finalY.toFloat())
        val msg = "Долгое нажатие в координатах ($finalX, $finalY) • $displayLabel"
        _lastActionReport.value = msg
        mainHandler.postDelayed({ inspectCurrentScreen(silentOverlayStatus = true) }, 800L)
        return msg
    }

    fun swipeExactPixels(startX: Int, startY: Int, endX: Int, endY: Int): String {
        val (screenW, screenH) = getScreenSizePixels()
        val sx = startX.coerceIn(5, screenW - 5).toFloat()
        val sy = startY.coerceIn(5, screenH - 5).toFloat()
        val ex = endX.coerceIn(5, screenW - 5).toFloat()
        val ey = endY.coerceIn(5, screenH - 5).toFloat()

        AuraOverlayManager.updateStatus(
            AuraOverlayVisualState.ACTING,
            "Свайп (${sx.toInt()},${sy.toInt()}) -> (${ex.toInt()},${ey.toInt()})"
        )
        AuraOverlayManager.showTapCrosshair(ex.toInt(), ey.toInt(), "Свайп")

        val path = Path().apply {
            moveTo(sx, sy)
            lineTo(ex, ey)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 340L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)

        val msg = "Свайп по координатам (${sx.toInt()},${sy.toInt()}) -> (${ex.toInt()},${ey.toInt()})"
        _lastActionReport.value = msg
        mainHandler.postDelayed({ inspectCurrentScreen(silentOverlayStatus = true) }, 700L)
        return msg
    }

    fun clickElementByText(targetText: String): String {
        val clean = targetText.trim()
        if (clean.isEmpty()) return "Не указан элемент для нажатия"

        AuraOverlayManager.updateStatus(
            AuraOverlayVisualState.ANALYZING,
            "Ищу по контексту «${clean.take(22)}»..."
        )

        // 1. Check if target is coordinates like "540,1200"
        val coordMatch = Regex("^(\\d{2,4})\\s*[,;:]\\s*(\\d{2,4})$").find(clean)
        if (coordMatch != null) {
            val cx = coordMatch.groupValues[1].toIntOrNull() ?: 500
            val cy = coordMatch.groupValues[2].toIntOrNull() ?: 500
            return tapExactPixel(cx, cy, clean)
        }

        // 2. Check if user specified an index (#1, 2, "первую", "первое видео", "первый результат")
        val indexMatch = Regex("^#?(\\d{1,2})$").find(clean)
        if (indexMatch != null) {
            val idx = indexMatch.groupValues[1].toIntOrNull() ?: 1
            return clickElementByIndex(idx)
        }

        val lowerClean = clean.lowercase()
        val elements = inspectCurrentScreen(silentOverlayStatus = true)

        if (lowerClean.contains("перв") && (lowerClean.contains("видео") || lowerClean.contains("результат") || lowerClean.contains("чат") || lowerClean.contains("элемент") || lowerClean.contains("кнопк") || lowerClean.contains("трек"))) {
            val contentItem = elements.firstOrNull { it.isClickable && it.yPercent in 18..85 }
                ?: elements.firstOrNull { it.isClickable }
            if (contentItem != null) {
                return clickElementByIndex(contentItem.index, "Первый элемент контента")
            }
        }
        if (lowerClean.contains("втор") && (lowerClean.contains("видео") || lowerClean.contains("результат") || lowerClean.contains("чат") || lowerClean.contains("кнопк") || lowerClean.contains("трек"))) {
            val contentItems = elements.filter { it.isClickable && it.yPercent in 18..85 }
            val secondItem = contentItems.getOrNull(1) ?: elements.filter { it.isClickable }.getOrNull(1)
            if (secondItem != null) {
                return clickElementByIndex(secondItem.index, "Второй элемент контента")
            }
        }

        // 3. Try matching in indexed ScreenUiElements first so we have exact coordinates & crosshair
        val candidateTerms = buildSearchTerms(lowerClean)
        val bestElement = elements.firstOrNull { el ->
            val lbl = el.label.lowercase()
            val vid = el.viewId.lowercase()
            (el.isClickable || el.isEditable) && candidateTerms.any { term -> lbl == term || vid == term }
        } ?: elements.firstOrNull { el ->
            val lbl = el.label.lowercase()
            val vid = el.viewId.lowercase()
            candidateTerms.any { term -> lbl.contains(term) || vid.contains(term) }
        }

        if (bestElement != null) {
            return clickElementByIndex(bestElement.index, clean)
        }

        // 4. Fallback to live AccessibilityNodeInfo tree traversal
        val root = rootInActiveWindow ?: windows.firstOrNull()?.root
        if (root != null) {
            val found = findNodeByTerms(root, candidateTerms)
            if (found != null) {
                val bounds = Rect()
                found.getBoundsInScreen(bounds)
                val cx = bounds.centerX()
                val cy = bounds.centerY()
                AuraOverlayManager.updateStatus(
                    AuraOverlayVisualState.ACTING,
                    "Нажимаю «${clean.take(20)}» ($cx, $cy)"
                )
                if (!bounds.isEmpty) {
                    AuraOverlayManager.showTapCrosshair(cx, cy, clean)
                }
                val clicked = performClickOnNodeOrParent(found)
                if (!clicked && !bounds.isEmpty) {
                    dispatchTapPixel(bounds.exactCenterX(), bounds.exactCenterY())
                }
                val msg = "Нажат элемент «$clean» ($cx, $cy)"
                _lastActionReport.value = msg
                mainHandler.postDelayed({ inspectCurrentScreen(silentOverlayStatus = true) }, 750L)
                return msg
            }
        }

        val msg = "Элемент «$clean» не найден (проверено ${elements.size} эл.)"
        _lastActionReport.value = msg
        AuraOverlayManager.updateStatus(AuraOverlayVisualState.IDLE, msg)
        return msg
    }

    fun longClickElement(targetText: String): String {
        val clean = targetText.trim()
        val elements = inspectCurrentScreen(silentOverlayStatus = true)
        val terms = buildSearchTerms(clean.lowercase())
        val root = rootInActiveWindow ?: windows.firstOrNull()?.root
        if (root != null) {
            val node = findNodeByTerms(root, terms)
            if (node != null) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                val cx = bounds.centerX()
                val cy = bounds.centerY()
                AuraOverlayManager.updateStatus(
                    AuraOverlayVisualState.ACTING,
                    "Долгое нажатие «${clean.take(18)}» ($cx, $cy)"
                )
                if (!bounds.isEmpty) {
                    AuraOverlayManager.showTapCrosshair(cx, cy, clean)
                }
                val ok = node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) ||
                    node.parent?.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) == true
                if (!ok && !bounds.isEmpty) {
                    dispatchLongPressPixel(bounds.exactCenterX(), bounds.exactCenterY())
                }
                val msg = "Долгое нажатие на «$clean» ($cx, $cy)"
                _lastActionReport.value = msg
                return msg
            }
        }
        val fallbackEl = elements.firstOrNull { el ->
            terms.any { t -> el.label.lowercase().contains(t) }
        }
        if (fallbackEl != null) {
            AuraOverlayManager.showTapCrosshair(fallbackEl.centerX, fallbackEl.centerY, fallbackEl.label)
            dispatchLongPressPixel(fallbackEl.centerX.toFloat(), fallbackEl.centerY.toFloat())
            val msg = "Долгое нажатие на «${fallbackEl.label}» (${fallbackEl.centerX}, ${fallbackEl.centerY})"
            _lastActionReport.value = msg
            return msg
        }
        return "Элемент «$clean» для долгого нажатия не найден"
    }

    fun performGlobalSystemAction(actionName: String): String {
        val code = when (actionName.uppercase()) {
            "BACK", "НАЗАД", "GLOBAL_BACK" -> GLOBAL_ACTION_BACK
            "HOME", "ДОМОЙ", "GLOBAL_HOME" -> GLOBAL_ACTION_HOME
            "RECENTS", "НЕДАВНИЕ", "GLOBAL_RECENTS" -> GLOBAL_ACTION_RECENTS
            "NOTIFICATIONS", "УВЕДОМЛЕНИЯ", "GLOBAL_NOTIFICATIONS" -> GLOBAL_ACTION_NOTIFICATIONS
            "QUICK_SETTINGS", "БЫСТРЫЕ_НАСТРОЙКИ", "GLOBAL_QUICK_SETTINGS" -> GLOBAL_ACTION_QUICK_SETTINGS
            "LOCK_SCREEN", "БЛОКИРОВКА", "GLOBAL_LOCK_SCREEN" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) GLOBAL_ACTION_LOCK_SCREEN else GLOBAL_ACTION_HOME
            }
            "SCREENSHOT", "СКРИНШОТ", "GLOBAL_SCREENSHOT" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) GLOBAL_ACTION_TAKE_SCREENSHOT else GLOBAL_ACTION_NOTIFICATIONS
            }
            "SPLIT_SCREEN", "РАЗДЕЛИТЬ_ЭКРАН" -> GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN
            else -> GLOBAL_ACTION_BACK
        }
        AuraOverlayManager.updateStatus(
            AuraOverlayVisualState.ACTING,
            "Системное действие: $actionName"
        )
        val ok = performGlobalAction(code)
        val report = if (ok) {
            "Системное действие выполнено: $actionName"
        } else {
            "Запрошено системное действие: $actionName"
        }
        _lastActionReport.value = report
        return report
    }

    fun inputTextIntoAppField(
        targetHintOrField: String,
        textToType: String,
        autoSubmit: Boolean = true,
        targetX: Int = -1,
        targetY: Int = -1
    ): String {
        val root = rootInActiveWindow ?: windows.firstOrNull()?.root
            ?: return "Активное окно приложения недоступно"

        AuraOverlayManager.updateStatus(
            AuraOverlayVisualState.ACTING,
            "Ввожу текст: «${textToType.take(22)}»"
        )

        // If Gemini provided an element #index as targetHintOrField, check if that element exists
        val idxMatch = Regex("^#?(\\d{1,2})$").find(targetHintOrField.trim())
        if (idxMatch != null) {
            val idx = idxMatch.groupValues[1].toIntOrNull()
            val el = _screenElements.value.firstOrNull { it.index == idx }
            if (el != null) {
                val nodeByBounds = findNodeByTextOrBounds(root, el.label, el.bounds)
                if (nodeByBounds != null && (nodeByBounds.isEditable || nodeByBounds.className?.toString()?.contains("EditText") == true)) {
                    AuraOverlayManager.showTapCrosshair(el.centerX, el.centerY, el.label)
                    return typeAndSubmitIntoNode(nodeByBounds, textToType, autoSubmit)
                } else {
                    // Tap the search bar / field first to activate EditText, then type!
                    AuraOverlayManager.showTapCrosshair(el.centerX, el.centerY, el.label)
                    if (nodeByBounds != null) {
                        performClickOnNodeOrParent(nodeByBounds)
                    } else {
                        dispatchTapPixel(el.centerX.toFloat(), el.centerY.toFloat())
                    }
                    mainHandler.postDelayed({
                        val refreshedRoot = rootInActiveWindow ?: windows.firstOrNull()?.root
                        val newlyOpenedInput = refreshedRoot?.let { findEditableNode(it, "") }
                        if (newlyOpenedInput != null) {
                            typeAndSubmitIntoNode(newlyOpenedInput, textToType, autoSubmit)
                        }
                    }, 480L)
                    return "Нажато поле #${el.index} (${el.centerX},${el.centerY}) и вводится «$textToType»"
                }
            }
        }

        // If Gemini provided explicit coordinates (targetX, targetY) for the input box
        if (targetX > 0 && targetY > 0) {
            AuraOverlayManager.showTapCrosshair(targetX, targetY, "Поле ввода")
            val nodeAtPoint = findEditableNodeAtPoint(root, targetX, targetY)
            if (nodeAtPoint != null) {
                return typeAndSubmitIntoNode(nodeAtPoint, textToType, autoSubmit)
            }
            // Tap coordinates to open input box, then type into focused EditText
            dispatchTapPixel(targetX.toFloat(), targetY.toFloat())
            mainHandler.postDelayed({
                val refreshedRoot = rootInActiveWindow ?: windows.firstOrNull()?.root
                val newlyOpenedInput = refreshedRoot?.let { findEditableNode(it, targetHintOrField) }
                if (newlyOpenedInput != null) {
                    typeAndSubmitIntoNode(newlyOpenedInput, textToType, autoSubmit)
                }
            }, 500L)
            return "Активировано поле ($targetX, $targetY) и вводится «$textToType»"
        }

        val targetNode = findEditableNode(root, targetHintOrField)
        if (targetNode != null) {
            val b = Rect()
            targetNode.getBoundsInScreen(b)
            if (!b.isEmpty) {
                AuraOverlayManager.showTapCrosshair(b.centerX(), b.centerY(), "Ввод текста")
            }
            return typeAndSubmitIntoNode(targetNode, textToType, autoSubmit)
        }

        // Smart auto-expand: if no EditText is visible yet, click the Search icon/bar first!
        val searchTrigger = findNodeByTerms(root, listOf("поиск", "найти", "search", "сообщение", "message", "write", "искать"))
        if (searchTrigger != null) {
            val b = Rect()
            searchTrigger.getBoundsInScreen(b)
            if (!b.isEmpty) {
                AuraOverlayManager.showTapCrosshair(b.centerX(), b.centerY(), "Открытие поиска")
            }
            performClickOnNodeOrParent(searchTrigger)
            mainHandler.postDelayed({
                val refreshedRoot = rootInActiveWindow ?: windows.firstOrNull()?.root
                val newlyOpenedInput = refreshedRoot?.let { findEditableNode(it, targetHintOrField) }
                if (newlyOpenedInput != null) {
                    typeAndSubmitIntoNode(newlyOpenedInput, textToType, autoSubmit)
                }
            }, 480L)
            val msg = "Открыто поле поиска и вводится «$textToType»"
            _lastActionReport.value = msg
            return msg
        }

        val msg = "Поле ввода в текущем приложении не найдено"
        _lastActionReport.value = msg
        return msg
    }

    private fun typeAndSubmitIntoNode(
        targetNode: AccessibilityNodeInfo,
        textToType: String,
        autoSubmit: Boolean
    ): String {
        targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                textToType
            )
        }
        val setOk = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (autoSubmit) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                targetNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }
            mainHandler.postDelayed({
                val refreshedRoot = rootInActiveWindow
                if (refreshedRoot != null) {
                    val sendNode = findSendOrSearchButton(refreshedRoot)
                    if (sendNode != null) {
                        val sb = Rect()
                        sendNode.getBoundsInScreen(sb)
                        if (!sb.isEmpty) {
                            AuraOverlayManager.showTapCrosshair(sb.centerX(), sb.centerY(), "Отправить/Поиск")
                        }
                        performClickOnNodeOrParent(sendNode)
                    }
                }
            }, 380L)
        }
        val msg = if (setOk) {
            "Введён текст «$textToType»"
        } else {
            "Поле сфокусировано для «$textToType»"
        }
        _lastActionReport.value = msg
        AuraOverlayManager.updateStatus(AuraOverlayVisualState.IDLE, msg)
        mainHandler.postDelayed({ inspectCurrentScreen(silentOverlayStatus = true) }, 800L)
        return msg
    }

    fun scrollOrSwipeScreen(direction: String): String {
        val dir = direction.uppercase().trim()
        AuraOverlayManager.updateStatus(
            AuraOverlayVisualState.ACTING,
            "Прокрутка экрана: $dir"
        )
        val root = rootInActiveWindow
        if (root != null) {
            val scrollable = findScrollableNode(root)
            if (scrollable != null) {
                val action = when (dir) {
                    "UP", "ВВЕРХ", "BACKWARD", "НАЗАД" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    else -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                }
                if (scrollable.performAction(action)) {
                    val msg = "Прокрутка экрана ($dir)"
                    _lastActionReport.value = msg
                    mainHandler.postDelayed({ inspectCurrentScreen(silentOverlayStatus = true) }, 550L)
                    return msg
                }
            }
        }

        val metrics = resources.displayMetrics
        val w = metrics.widthPixels.toFloat().coerceAtLeast(800f)
        val h = metrics.heightPixels.toFloat().coerceAtLeast(1600f)

        val (startX, startY, endX, endY) = when (dir) {
            "UP", "ВВЕРХ" -> listOf(w * 0.5f, h * 0.30f, w * 0.5f, h * 0.78f)
            "LEFT", "ВЛЕВО" -> listOf(w * 0.82f, h * 0.5f, w * 0.18f, h * 0.5f)
            "RIGHT", "ВПРАВО" -> listOf(w * 0.18f, h * 0.5f, w * 0.82f, h * 0.5f)
            else -> listOf(w * 0.5f, h * 0.78f, w * 0.5f, h * 0.25f)
        }

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 320L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)

        val msg = "Жест свайпа выполнен: $dir"
        _lastActionReport.value = msg
        mainHandler.postDelayed({ inspectCurrentScreen(silentOverlayStatus = true) }, 650L)
        return msg
    }

    fun tapAtPercent(xPercent: Float, yPercent: Float, reasonLabel: String = ""): String {
        val metrics = resources.displayMetrics
        val x = (metrics.widthPixels * (xPercent.coerceIn(1f, 99f) / 100f)).toInt()
        val y = (metrics.heightPixels * (yPercent.coerceIn(1f, 99f) / 100f)).toInt()
        return tapExactPixel(x, y, reasonLabel.ifBlank { "${xPercent.toInt()}%, ${yPercent.toInt()}%" })
    }

    private fun dispatchTapPixel(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 80L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    private fun dispatchLongPressPixel(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 650L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    fun readActiveScreenSummary(): String {
        val elements = inspectCurrentScreen(silentOverlayStatus = false)
        if (elements.isEmpty()) {
            return "Экран пуст или доступ к окну ещё не получен"
        }
        val clickable = elements.filter { it.isClickable || it.isEditable }.take(14)
        val labels = (if (clickable.isNotEmpty()) clickable else elements.take(14)).map {
            "#${it.index} ${it.label} (${it.centerX},${it.centerY})"
        }
        return "На экране (${elements.size} эл.): " + labels.joinToString(" • ")
    }

    private fun buildSearchTerms(queryLower: String): List<String> {
        val terms = mutableSetOf(queryLower)
        for ((key, syns) in semanticSynonyms) {
            if (queryLower.contains(key)) {
                terms.addAll(syns)
            }
        }
        return terms.toList()
    }

    private fun findClickableNodeAtPoint(root: AccessibilityNodeInfo, xPx: Int, yPx: Int): AccessibilityNodeInfo? {
        var bestNode: AccessibilityNodeInfo? = null
        var smallestArea = Int.MAX_VALUE

        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || !node.isVisibleToUser) return
            val b = Rect()
            node.getBoundsInScreen(b)
            if (b.contains(xPx, yPx)) {
                val area = b.width() * b.height()
                if ((node.isClickable || node.isEditable) && area in 100 until smallestArea) {
                    smallestArea = area
                    bestNode = node
                }
                for (i in 0 until node.childCount) {
                    walk(node.getChild(i))
                }
            }
        }
        walk(root)
        return bestNode
    }

    private fun findEditableNodeAtPoint(root: AccessibilityNodeInfo, xPx: Int, yPx: Int): AccessibilityNodeInfo? {
        return traverseForMatch(root) { node ->
            if (!node.isVisibleToUser) return@traverseForMatch false
            val isEdit = node.isEditable || node.className?.toString()?.contains("EditText", ignoreCase = true) == true
            if (!isEdit) return@traverseForMatch false
            val b = Rect()
            node.getBoundsInScreen(b)
            b.contains(xPx, yPx) || (kotlin.math.abs(b.centerX() - xPx) < 90 && kotlin.math.abs(b.centerY() - yPx) < 90)
        }
    }

    private fun findNodeByTerms(root: AccessibilityNodeInfo, terms: List<String>): AccessibilityNodeInfo? {
        for (term in terms) {
            val directMatches = root.findAccessibilityNodeInfosByText(term)
            if (!directMatches.isNullOrEmpty()) {
                val clickableMatch = directMatches.firstOrNull { it.isClickable || it.parent?.isClickable == true }
                if (clickableMatch != null) return clickableMatch
            }
        }
        // First pass: prefer clickable nodes matching any term
        val clickableResult = traverseForMatch(root) { node ->
            if (!node.isVisibleToUser) return@traverseForMatch false
            val text = node.text?.toString()?.lowercase().orEmpty()
            val desc = node.contentDescription?.toString()?.lowercase().orEmpty()
            val viewId = node.viewIdResourceName?.lowercase().orEmpty()
            val isClick = node.isClickable || node.parent?.isClickable == true
            isClick && terms.any { term ->
                text.contains(term) || desc.contains(term) || viewId.contains(term)
            }
        }
        if (clickableResult != null) return clickableResult

        // Second pass: any visible node matching term
        return traverseForMatch(root) { node ->
            if (!node.isVisibleToUser) return@traverseForMatch false
            val text = node.text?.toString()?.lowercase().orEmpty()
            val desc = node.contentDescription?.toString()?.lowercase().orEmpty()
            val viewId = node.viewIdResourceName?.lowercase().orEmpty()
            terms.any { term ->
                text.contains(term) || desc.contains(term) || viewId.contains(term)
            }
        }
    }

    private fun findNodeByTextOrBounds(
        root: AccessibilityNodeInfo,
        label: String,
        targetBounds: Rect
    ): AccessibilityNodeInfo? {
        val lower = label.lowercase()
        return traverseForMatch(root) { node ->
            if (!node.isVisibleToUser) return@traverseForMatch false
            val b = Rect()
            node.getBoundsInScreen(b)
            val sameCenter = kotlin.math.abs(b.centerX() - targetBounds.centerX()) < 28 &&
                kotlin.math.abs(b.centerY() - targetBounds.centerY()) < 28
            val t = node.text?.toString()?.lowercase().orEmpty()
            val d = node.contentDescription?.toString()?.lowercase().orEmpty()
            sameCenter || (t.isNotEmpty() && t == lower) || (d.isNotEmpty() && d == lower)
        }
    }

    private fun findEditableNode(root: AccessibilityNodeInfo, hintQuery: String): AccessibilityNodeInfo? {
        val lower = hintQuery.trim().lowercase()
        if (lower.isNotEmpty()) {
            val matched = traverseForMatch(root) { node ->
                node.isEditable && (
                    node.text?.toString()?.lowercase()?.contains(lower) == true ||
                        node.contentDescription?.toString()?.lowercase()?.contains(lower) == true ||
                        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                            node.hintText?.toString()?.lowercase()?.contains(lower) == true)
                    )
            }
            if (matched != null) return matched
        }
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && focused.isEditable) return focused

        return traverseForMatch(root) { it.isEditable || it.className?.toString()?.contains("EditText") == true }
    }

    private fun findSendOrSearchButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val keywords = listOf("отправить", "send", "поиск", "search", "найти", "далее", "done", "ok")
        return traverseForMatch(root) { node ->
            val t = node.text?.toString()?.lowercase().orEmpty()
            val d = node.contentDescription?.toString()?.lowercase().orEmpty()
            val id = node.viewIdResourceName?.lowercase().orEmpty()
            (node.isClickable || node.parent?.isClickable == true) &&
                keywords.any { kw -> t == kw || d.contains(kw) || id.contains("send") || id.contains("search_button") }
        }
    }

    private fun findScrollableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return traverseForMatch(root) { it.isScrollable }
    }

    private fun performClickOnNodeOrParent(node: AccessibilityNodeInfo?): Boolean {
        var current = node
        var depth = 0
        while (current != null && depth < 7) {
            if (current.isClickable) {
                if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
            }
            current = current.parent
            depth++
        }
        return false
    }

    private fun traverseForMatch(
        node: AccessibilityNodeInfo?,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node)) return node
        for (i in 0 until node.childCount) {
            val childResult = traverseForMatch(node.getChild(i), predicate)
            if (childResult != null) return childResult
        }
        return null
    }
}
