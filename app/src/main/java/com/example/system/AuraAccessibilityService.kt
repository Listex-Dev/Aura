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

        private val mainHandler = Handler(Looper.getMainLooper())

        fun runDelayed(delayMs: Long, block: (AuraAccessibilityService) -> Unit): Boolean {
            val svc = _instance.value ?: return false
            mainHandler.postDelayed({
                _instance.value?.let(block)
            }, delayMs)
            return true
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        _instance.value = this
        _isConnected.value = true
        _lastActionReport.value = "Полное управление Android (Accessibility) активно"
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString().orEmpty()
        if (pkg.isNotBlank() && pkg != packageName) {
            _activePackage.value = pkg
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
        val ok = performGlobalAction(code)
        val report = if (ok) {
            "Системное действие выполнено: $actionName"
        } else {
            "Запрошено системное действие: $actionName"
        }
        _lastActionReport.value = report
        return report
    }

    fun clickElementByText(targetText: String): String {
        val clean = targetText.trim()
        if (clean.isEmpty()) return "Не указан текст элемента для нажатия"

        val root = rootInActiveWindow ?: windows.firstOrNull()?.root
        if (root != null) {
            val found = findNodeByTextOrDesc(root, clean)
            if (found != null) {
                val clicked = performClickOnNodeOrParent(found)
                if (clicked) {
                    val msg = "Нажат элемент «$clean»"
                    _lastActionReport.value = msg
                    return msg
                }
                val bounds = Rect()
                found.getBoundsInScreen(bounds)
                if (!bounds.isEmpty) {
                    dispatchTapPixel(bounds.exactCenterX(), bounds.exactCenterY())
                    val msg = "Касание по элементу «$clean» (${bounds.centerX()}, ${bounds.centerY()})"
                    _lastActionReport.value = msg
                    return msg
                }
            }
        }
        val msg = "Элемент «$clean» не найден в текущем окне"
        _lastActionReport.value = msg
        return msg
    }

    fun inputTextIntoAppField(
        targetHintOrField: String,
        textToType: String,
        autoSubmit: Boolean = true
    ): String {
        val root = rootInActiveWindow ?: windows.firstOrNull()?.root
            ?: return "Активное окно приложения недоступно"

        val targetNode = findEditableNode(root, targetHintOrField)
        if (targetNode != null) {
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
                "Введён текст «$textToType» в поле приложения"
            } else {
                "Поле сфокусировано для текста «$textToType»"
            }
            _lastActionReport.value = msg
            return msg
        }

        val msg = "Поле ввода в текущем приложении не найдено"
        _lastActionReport.value = msg
        return msg
    }

    fun scrollOrSwipeScreen(direction: String): String {
        val dir = direction.uppercase().trim()
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
                    return msg
                }
            }
        }

        // Fallback to real gesture swipe across display
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels.toFloat().coerceAtLeast(800f)
        val h = metrics.heightPixels.toFloat().coerceAtLeast(1600f)

        val (startX, startY, endX, endY) = when (dir) {
            "UP", "ВВЕРХ" -> listOf(w * 0.5f, h * 0.30f, w * 0.5f, h * 0.78f)
            "LEFT", "ВЛЕВО" -> listOf(w * 0.82f, h * 0.5f, w * 0.18f, h * 0.5f)
            "RIGHT", "ВПРАВО" -> listOf(w * 0.18f, h * 0.5f, w * 0.82f, h * 0.5f)
            else -> listOf(w * 0.5f, h * 0.78f, w * 0.5f, h * 0.25f) // DOWN / forward scroll
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
        return msg
    }

    fun tapAtPercent(xPercent: Float, yPercent: Float): String {
        val metrics = resources.displayMetrics
        val x = (metrics.widthPixels * (xPercent.coerceIn(1f, 99f) / 100f))
        val y = (metrics.heightPixels * (yPercent.coerceIn(1f, 99f) / 100f))
        dispatchTapPixel(x, y)
        val msg = "Касание экрана (${xPercent.toInt()}%, ${yPercent.toInt()}%)"
        _lastActionReport.value = msg
        return msg
    }

    private fun dispatchTapPixel(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 65L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    fun readActiveScreenSummary(): String {
        val root = rootInActiveWindow ?: windows.firstOrNull()?.root
            ?: return "Экран пуст или доступ к окну ещё не получен"
        val items = mutableListOf<String>()
        collectVisibleTexts(root, items)
        val distinct = items.distinct().take(18)
        return if (distinct.isEmpty()) {
            "На экране нет текстовых элементов"
        } else {
            "На экране: " + distinct.joinToString(" • ")
        }
    }

    private fun collectVisibleTexts(node: AccessibilityNodeInfo?, out: MutableList<String>) {
        if (node == null || out.size >= 25) return
        val t = node.text?.toString()?.trim().orEmpty()
        val d = node.contentDescription?.toString()?.trim().orEmpty()
        if (t.isNotEmpty() && t.length <= 80) out.add(t)
        else if (d.isNotEmpty() && d.length <= 80) out.add(d)
        for (i in 0 until node.childCount) {
            collectVisibleTexts(node.getChild(i), out)
        }
    }

    private fun findNodeByTextOrDesc(root: AccessibilityNodeInfo, query: String): AccessibilityNodeInfo? {
        val lower = query.lowercase()
        val directMatches = root.findAccessibilityNodeInfosByText(query)
        if (!directMatches.isNullOrEmpty()) {
            return directMatches.firstOrNull { it.isClickable } ?: directMatches.first()
        }
        return traverseForMatch(root) { node ->
            val text = node.text?.toString()?.lowercase().orEmpty()
            val desc = node.contentDescription?.toString()?.lowercase().orEmpty()
            val viewId = node.viewIdResourceName?.lowercase().orEmpty()
            text.contains(lower) || desc.contains(lower) || viewId.contains(lower)
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
        while (current != null && depth < 6) {
            if (current.isClickable) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
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
