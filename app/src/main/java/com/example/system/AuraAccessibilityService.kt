package com.example.system

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
    val bounds: Rect
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

        private val _screenStructureSummary = MutableStateFlow("Экран ещё не проанализирован")
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
        "поиск" to listOf("поиск", "найти", "search", "find", "query", "лупа", "искать"),
        "найти" to listOf("поиск", "найти", "search", "find", "query", "искать"),
        "отправить" to listOf("отправить", "send", "submit", "post", "готово", "done", "enter", "отослать"),
        "лайк" to listOf("нравится", "лайк", "like", "heart", "favorite", "класс", "оценить"),
        "нравится" to listOf("нравится", "лайк", "like", "heart", "favorite"),
        "подписаться" to listOf("подписаться", "subscribe", "follow", "читать", "вступить"),
        "закрыть" to listOf("закрыть", "close", "dismiss", "cancel", "отмена", "крестик"),
        "меню" to listOf("меню", "ещё", "еще", "more", "options", "menu", "три точки", "настройки"),
        "назад" to listOf("назад", "back", "navigate up", "вернуться"),
        "плей" to listOf("воспроизвести", "играть", "play", "пауза", "pause", "смотреть"),
        "включи" to listOf("воспроизвести", "играть", "play", "смотреть", "открыть")
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        _instance.value = this
        _isConnected.value = true
        val msg = "Полный контроль активен • Анализатор экрана включён"
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
            (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && now - lastAutoInspectTimeMs > 1400L)
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

    /**
     * Deeply analyzes all active windows on screen, indexing every button, input field,
     * tab, and interactive item with exact coordinates and descriptions for AI & user control.
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

        fun collectNodes(node: AccessibilityNodeInfo?) {
            if (node == null || nextIndex > 45) return
            if (!node.isVisibleToUser) return

            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.width() > 8 && bounds.height() > 8 &&
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

                val clickable = node.isClickable || (node.parent?.isClickable == true && (text.isNotEmpty() || desc.isNotEmpty()))
                val editable = node.isEditable || className.contains("EditText", ignoreCase = true)
                val scrollable = node.isScrollable
                val checkable = node.isCheckable

                val inferredLabel = when {
                    text.isNotEmpty() -> text
                    desc.isNotEmpty() -> desc
                    hint.isNotEmpty() -> hint
                    rawViewId.isNotEmpty() -> humanizeViewId(rawViewId)
                    else -> ""
                }

                if (inferredLabel.isNotEmpty() || editable) {
                    val finalLabel = inferredLabel.ifEmpty { "Поле ввода" }.take(65)
                    val category = when {
                        editable -> "ПОЛЕ ВВОДА"
                        checkable -> "ПЕРЕКЛЮЧАТЕЛЬ"
                        className.contains("Tab", ignoreCase = true) -> "ВКЛАДКА"
                        clickable -> "КНОПКА"
                        else -> "ТЕКСТ"
                    }

                    val xPct = ((bounds.exactCenterX() / screenW) * 100f).toInt().coerceIn(1, 99)
                    val yPct = ((bounds.exactCenterY() / screenH) * 100f).toInt().coerceIn(1, 99)
                    val signature = "${finalLabel.lowercase()}_${xPct / 4}_${yPct / 4}"

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
                                bounds = Rect(bounds)
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
        summaryBuilder.append("Экран ($shortPkg): кнопок $clickableCount, полей $inputCount. ")
        if (rawCandidates.isNotEmpty()) {
            val formatted = rawCandidates.take(22).joinToString(" | ") { el ->
                "[#${el.index} ${el.category}: \"${el.label}\" (${el.xPercent}%,${el.yPercent}%)]"
            }
            summaryBuilder.append(formatted)
        } else {
            summaryBuilder.append("Элементы не обнаружены")
        }

        val summaryText = summaryBuilder.toString()
        _screenStructureSummary.value = summaryText
        AuraOverlayManager.refreshScreenElementsOnOverlay(rawCandidates, _activePackage.value)

        if (!silentOverlayStatus) {
            val report = "Анализ экрана ($shortPkg): $clickableCount кнопок, $inputCount полей"
            _lastActionReport.value = report
            AuraOverlayManager.updateStatus(AuraOverlayVisualState.IDLE, report)
        }
        return rawCandidates
    }

    private fun humanizeViewId(rawId: String): String {
        val lower = rawId.lowercase()
        return when {
            lower.contains("search") -> "Поиск"
            lower.contains("send") -> "Отправить"
            lower.contains("like") || lower.contains("heart") -> "Нравится"
            lower.contains("share") -> "Поделиться"
            lower.contains("back") || lower.contains("up") -> "Назад"
            lower.contains("close") || lower.contains("dismiss") -> "Закрыть"
            lower.contains("more") || lower.contains("menu") -> "Меню"
            lower.contains("play") -> "Воспроизвести"
            lower.contains("pause") -> "Пауза"
            lower.contains("sub") -> "Подписаться"
            lower.contains("avatar") || lower.contains("profile") -> "Профиль"
            lower.contains("mic") || lower.contains("voice") -> "Голосовой ввод"
            else -> ""
        }
    }

    fun clickElementByIndex(index: Int): String {
        var elements = _screenElements.value
        if (elements.isEmpty()) {
            elements = inspectCurrentScreen(silentOverlayStatus = true)
        }
        val target = elements.firstOrNull { it.index == index }
            ?: elements.filter { it.isClickable || it.isEditable }.getOrNull(index - 1)
            ?: return "Элемент #$index не найден на экране"

        AuraOverlayManager.updateStatus(
            AuraOverlayVisualState.ACTING,
            "Нажимаю #${target.index} «${target.label.take(22)}»"
        )

        val root = rootInActiveWindow ?: windows.firstOrNull()?.root
        var nodeClicked = false
        if (root != null) {
            val matchedNode = findNodeByTextOrBounds(root, target.label, target.bounds)
            if (matchedNode != null) {
                nodeClicked = performClickOnNodeOrParent(matchedNode)
            }
        }
        // Always reinforce with hardware gesture tap if node action didn't confirm or for custom surfaces
        if (!nodeClicked) {
            dispatchTapPixel(target.centerX.toFloat(), target.centerY.toFloat())
        }

        val msg = "Нажата кнопка #${target.index} «${target.label.take(24)}» (${target.xPercent}%, ${target.yPercent}%)"
        _lastActionReport.value = msg
        mainHandler.postDelayed({ inspectCurrentScreen(silentOverlayStatus = true) }, 700L)
        return msg
    }

    fun clickElementByText(targetText: String): String {
        val clean = targetText.trim()
        if (clean.isEmpty()) return "Не указан элемент для нажатия"

        AuraOverlayManager.updateStatus(
            AuraOverlayVisualState.ANALYZING,
            "Ищу на экране «${clean.take(22)}»..."
        )

        // 1. Check if user specified an index (#1, 2, "первую", "первое видео", "первый результат")
        val indexMatch = Regex("^#?(\\d{1,2})$").find(clean)
        if (indexMatch != null) {
            val idx = indexMatch.groupValues[1].toIntOrNull() ?: 1
            return clickElementByIndex(idx)
        }

        val lowerClean = clean.lowercase()
        val elements = inspectCurrentScreen(silentOverlayStatus = true)

        if (lowerClean.contains("перв") && (lowerClean.contains("видео") || lowerClean.contains("результат") || lowerClean.contains("чат") || lowerClean.contains("элемент") || lowerClean.contains("кнопк"))) {
            val contentItem = elements.firstOrNull { it.isClickable && it.yPercent in 18..85 }
                ?: elements.firstOrNull { it.isClickable }
            if (contentItem != null) {
                return clickElementByIndex(contentItem.index)
            }
        }
        if (lowerClean.contains("втор") && (lowerClean.contains("видео") || lowerClean.contains("результат") || lowerClean.contains("чат") || lowerClean.contains("кнопк"))) {
            val contentItems = elements.filter { it.isClickable && it.yPercent in 18..85 }
            val secondItem = contentItems.getOrNull(1) ?: elements.filter { it.isClickable }.getOrNull(1)
            if (secondItem != null) {
                return clickElementByIndex(secondItem.index)
            }
        }

        // 2. Try matching in live AccessibilityNodeInfo tree using text, description, viewId, and synonyms
        val candidateTerms = buildSearchTerms(lowerClean)
        val root = rootInActiveWindow ?: windows.firstOrNull()?.root
        if (root != null) {
            val found = findNodeByTerms(root, candidateTerms)
            if (found != null) {
                val bounds = Rect()
                found.getBoundsInScreen(bounds)
                AuraOverlayManager.updateStatus(
                    AuraOverlayVisualState.ACTING,
                    "Нажимаю «${clean.take(22)}»"
                )
                val clicked = performClickOnNodeOrParent(found)
                if (!clicked && !bounds.isEmpty) {
                    dispatchTapPixel(bounds.exactCenterX(), bounds.exactCenterY())
                }
                val msg = "Нажат элемент «$clean»"
                _lastActionReport.value = msg
                mainHandler.postDelayed({ inspectCurrentScreen(silentOverlayStatus = true) }, 700L)
                return msg
            }
        }

        // 3. Fallback to indexed ScreenUiElements fuzzy match
        val bestElement = elements.firstOrNull { el ->
            val lbl = el.label.lowercase()
            val vid = el.viewId.lowercase()
            candidateTerms.any { term -> lbl.contains(term) || vid.contains(term) }
        }
        if (bestElement != null) {
            return clickElementByIndex(bestElement.index)
        }

        val msg = "Элемент «$clean» не найден (на экране ${elements.size} эл.)"
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
                AuraOverlayManager.updateStatus(
                    AuraOverlayVisualState.ACTING,
                    "Долгое нажатие «${clean.take(20)}»"
                )
                val ok = node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) ||
                    node.parent?.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) == true
                if (!ok && !bounds.isEmpty) {
                    dispatchLongPressPixel(bounds.exactCenterX(), bounds.exactCenterY())
                }
                val msg = "Долгое нажатие на «$clean» выполнено"
                _lastActionReport.value = msg
                return msg
            }
        }
        val fallbackEl = elements.firstOrNull { el ->
            terms.any { t -> el.label.lowercase().contains(t) }
        }
        if (fallbackEl != null) {
            dispatchLongPressPixel(fallbackEl.centerX.toFloat(), fallbackEl.centerY.toFloat())
            val msg = "Долгое нажатие на «${fallbackEl.label}»"
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
        autoSubmit: Boolean = true
    ): String {
        val root = rootInActiveWindow ?: windows.firstOrNull()?.root
            ?: return "Активное окно приложения недоступно"

        AuraOverlayManager.updateStatus(
            AuraOverlayVisualState.ACTING,
            "Ввожу текст: «${textToType.take(22)}»"
        )

        val targetNode = findEditableNode(root, targetHintOrField)
        if (targetNode != null) {
            return typeAndSubmitIntoNode(targetNode, textToType, autoSubmit)
        }

        // Smart auto-expand: if no EditText is visible yet, click the Search icon/bar first!
        val searchTrigger = findNodeByTerms(root, listOf("поиск", "найти", "search", "сообщение", "message", "write"))
        if (searchTrigger != null) {
            performClickOnNodeOrParent(searchTrigger)
            mainHandler.postDelayed({
                val refreshedRoot = rootInActiveWindow ?: windows.firstOrNull()?.root
                val newlyOpenedInput = refreshedRoot?.let { findEditableNode(it, targetHintOrField) }
                if (newlyOpenedInput != null) {
                    typeAndSubmitIntoNode(newlyOpenedInput, textToType, autoSubmit)
                }
            }, 450L)
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
                    sendNode?.let { performClickOnNodeOrParent(it) }
                }
            }, 350L)
        }
        val msg = if (setOk) {
            "Введён текст «$textToType»"
        } else {
            "Поле сфокусировано для «$textToType»"
        }
        _lastActionReport.value = msg
        AuraOverlayManager.updateStatus(AuraOverlayVisualState.IDLE, msg)
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
                    mainHandler.postDelayed({ inspectCurrentScreen(silentOverlayStatus = true) }, 500L)
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
        mainHandler.postDelayed({ inspectCurrentScreen(silentOverlayStatus = true) }, 600L)
        return msg
    }

    fun tapAtPercent(xPercent: Float, yPercent: Float): String {
        val metrics = resources.displayMetrics
        val x = (metrics.widthPixels * (xPercent.coerceIn(1f, 99f) / 100f))
        val y = (metrics.heightPixels * (yPercent.coerceIn(1f, 99f) / 100f))
        AuraOverlayManager.updateStatus(
            AuraOverlayVisualState.ACTING,
            "Касание экрана (${xPercent.toInt()}%, ${yPercent.toInt()}%)"
        )
        dispatchTapPixel(x, y)
        val msg = "Касание экрана (${xPercent.toInt()}%, ${yPercent.toInt()}%)"
        _lastActionReport.value = msg
        return msg
    }

    private fun dispatchTapPixel(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 75L)
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
        val clickable = elements.filter { it.isClickable || it.isEditable }.take(12)
        val labels = (if (clickable.isNotEmpty()) clickable else elements.take(12)).map {
            "#${it.index} ${it.label}"
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
            val sameCenter = kotlin.math.abs(b.centerX() - targetBounds.centerX()) < 24 &&
                kotlin.math.abs(b.centerY() - targetBounds.centerY()) < 24
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
