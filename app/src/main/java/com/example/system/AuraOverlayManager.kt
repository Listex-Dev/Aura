package com.example.system

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

enum class AuraOverlayVisualState(
    val badgeTitle: String,
    val ringColorHex: Int,
    val glowColorHex: Int
) {
    IDLE("Аура на связи", 0xFF00E5FF.toInt(), 0x4400E5FF),
    LISTENING("Слушаю вас...", 0xFF00E676.toInt(), 0x5500E676),
    ANALYZING("Анализирую экран...", 0xFFFFD740.toInt(), 0x55FFD740),
    ACTING("Выполняю действие...", 0xFFE040FB.toInt(), 0x55E040FB),
    SPEAKING("Говорю...", 0xFF7C4DFF.toInt(), 0x557C4DFF)
}

/**
 * Manages Aura's floating avatar & live action HUD over all applications.
 * Supports both TYPE_ACCESSIBILITY_OVERLAY (automatic when AccessibilityService is enabled)
 * and TYPE_APPLICATION_OVERLAY (when SYSTEM_ALERT_WINDOW is granted).
 */
@SuppressLint("StaticFieldLeak")
object AuraOverlayManager {

    private val mainHandler = Handler(Looper.getMainLooper())

    private val _isOverlayShowing = MutableStateFlow(false)
    val isOverlayShowing: StateFlow<Boolean> = _isOverlayShowing.asStateFlow()

    private val _visualState = MutableStateFlow(AuraOverlayVisualState.IDLE)
    val visualState: StateFlow<AuraOverlayVisualState> = _visualState.asStateFlow()

    private val _currentActionText = MutableStateFlow("Готова управлять любым приложением")
    val currentActionText: StateFlow<String> = _currentActionText.asStateFlow()

    private val _isExpanded = MutableStateFlow(false)
    val isExpanded: StateFlow<Boolean> = _isExpanded.asStateFlow()

    private var windowManager: WindowManager? = null
    private var rootView: FrameLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var attachedContext: Context? = null
    private var isUsingAccessibilityWindow = false

    // UI references inside floating view
    private var avatarImageView: ImageView? = null
    private var avatarRingContainer: FrameLayout? = null
    private var statusTitleView: TextView? = null
    private var statusSubtitleView: TextView? = null
    private var expandedPanel: LinearLayout? = null
    private var elementsContainer: LinearLayout? = null
    private var screenSummaryTextView: TextView? = null

    // Callbacks wired from ViewModel / AccessibilityService
    var onMicCommandRequested: (() -> Unit)? = null
    var onQuickCommandRequested: ((String) -> Unit)? = null

    fun updateStatus(state: AuraOverlayVisualState, actionDescription: String) {
        _visualState.value = state
        _currentActionText.value = actionDescription
        mainHandler.post {
            refreshOverlayUi()
        }
    }

    fun showOverlay(context: Context, preferAccessibilityOverlay: Boolean = false): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { showOverlay(context, preferAccessibilityOverlay) }
            return true
        }

        val accessibilitySvc = AuraAccessibilityService.instance.value
        val canDrawAppOverlay = Settings.canDrawOverlays(context.applicationContext)

        val targetContext: Context
        val windowType: Int
        val useAccType: Boolean

        if (accessibilitySvc != null && (preferAccessibilityOverlay || !canDrawAppOverlay)) {
            targetContext = accessibilitySvc
            windowType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            useAccType = true
        } else if (canDrawAppOverlay) {
            targetContext = context.applicationContext
            windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            useAccType = false
        } else if (accessibilitySvc != null) {
            targetContext = accessibilitySvc
            windowType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            useAccType = true
        } else {
            return false
        }

        if (_isOverlayShowing.value && rootView != null) {
            if (isUsingAccessibilityWindow == useAccType) {
                refreshOverlayUi()
                return true
            } else {
                hideOverlay()
            }
        }

        return try {
            val wm = targetContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = dp(targetContext, 12)
                y = dp(targetContext, 110)
            }

            val view = buildOverlayView(targetContext)
            wm.addView(view, params)

            windowManager = wm
            rootView = view
            layoutParams = params
            attachedContext = targetContext
            isUsingAccessibilityWindow = useAccType
            _isOverlayShowing.value = true
            refreshOverlayUi()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun hideOverlay() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { hideOverlay() }
            return
        }
        try {
            val view = rootView
            val wm = windowManager
            if (view != null && wm != null) {
                wm.removeViewImmediate(view)
            }
        } catch (_: Exception) {
        } finally {
            rootView = null
            windowManager = null
            layoutParams = null
            attachedContext = null
            _isOverlayShowing.value = false
            _isExpanded.value = false
        }
    }

    fun toggleOverlay(context: Context): Boolean {
        return if (_isOverlayShowing.value) {
            hideOverlay()
            false
        } else {
            showOverlay(context)
        }
    }

    fun refreshScreenElementsOnOverlay(elements: List<ScreenUiElement>, activePkg: String) {
        mainHandler.post {
            val ctx = attachedContext ?: return@post
            val container = elementsContainer ?: return@post
            val summaryTv = screenSummaryTextView ?: return@post

            val shortPkg = activePkg.substringAfterLast('.').ifBlank { "Экран" }
            summaryTv.text = "Приложение: $shortPkg • Найдено элементов: ${elements.size}"

            container.removeAllViews()
            val clickableItems = elements.filter { it.isClickable || it.isEditable }.take(12)
            if (clickableItems.isEmpty()) {
                val emptyChip = createActionChip(ctx, "Нет активных кнопок (Нажмите «Обновить»)", 0xFF263238.toInt()) {
                    AuraAccessibilityService.instance.value?.inspectCurrentScreen()
                }
                container.addView(emptyChip)
                return@post
            }

            for (el in clickableItems) {
                val prefix = if (el.isEditable) "⌨️" else "👆"
                val label = "$prefix #${el.index} ${el.label.take(20)}"
                val chip = createActionChip(ctx, label, 0xFF1E293B.toInt()) {
                    updateStatus(
                        AuraOverlayVisualState.ACTING,
                        "Нажимаю #${el.index} «${el.label.take(18)}»"
                    )
                    AuraAccessibilityService.instance.value?.clickElementByIndex(el.index)
                }
                container.addView(chip)
            }
        }
    }

    private fun refreshOverlayUi() {
        val ctx = attachedContext ?: return
        val state = _visualState.value
        val actionText = _currentActionText.value

        statusTitleView?.text = state.badgeTitle
        statusTitleView?.setTextColor(state.ringColorHex)
        statusSubtitleView?.text = actionText

        avatarRingContainer?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFF0F172A.toInt())
            setStroke(dp(ctx, 3), state.ringColorHex)
        }

        expandedPanel?.visibility = if (_isExpanded.value) View.VISIBLE else View.GONE
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildOverlayView(context: Context): FrameLayout {
        val root = FrameLayout(context)

        val mainCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(context, 26).toFloat()
                setColor(0xEB0B1120.toInt())
                setStroke(dp(context, 1), 0x5538BDF8)
            }
            setPadding(dp(context, 10), dp(context, 8), dp(context, 12), dp(context, 8))
        }

        // Top compact row: [Circular Avatar] + [Live Status & What Aura Is Doing] + [Quick Mic] + [Analyze]
        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val ringFrame = FrameLayout(context).apply {
            val size = dp(context, 50)
            layoutParams = LinearLayout.LayoutParams(size, size)
            setPadding(dp(context, 3), dp(context, 3), dp(context, 3), dp(context, 3))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF0F172A.toInt())
                setStroke(dp(context, 3), _visualState.value.ringColorHex)
            }
        }

        val avatarImg = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            val circularBmp = loadCircularAvatarBitmap(context)
            if (circularBmp != null) {
                setImageBitmap(circularBmp)
            } else {
                setImageResource(R.drawable.img_aura_avatar_1792153220147)
            }
        }
        ringFrame.addView(avatarImg)
        avatarImageView = avatarImg
        avatarRingContainer = ringFrame

        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp(context, 175), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(context, 10)
                marginEnd = dp(context, 6)
            }
        }

        val titleTv = TextView(context).apply {
            text = _visualState.value.badgeTitle
            setTextColor(_visualState.value.ringColorHex)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        val subtitleTv = TextView(context).apply {
            text = _currentActionText.value
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }

        textCol.addView(titleTv)
        textCol.addView(subtitleTv)
        statusTitleView = titleTv
        statusSubtitleView = subtitleTv

        // Quick Mic button on floating HUD
        val micBtn = TextView(context).apply {
            text = "🎤"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            val btnSize = dp(context, 36)
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                marginEnd = dp(context, 6)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF0284C7.toInt())
            }
            setOnClickListener {
                updateStatus(AuraOverlayVisualState.LISTENING, "Слушаю голосовую команду...")
                onMicCommandRequested?.invoke()
            }
        }

        // Quick Screen Scan button on floating HUD
        val scanBtn = TextView(context).apply {
            text = "🔍"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            val btnSize = dp(context, 36)
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF4F46E5.toInt())
            }
            setOnClickListener {
                _isExpanded.value = true
                refreshOverlayUi()
                updateStatus(AuraOverlayVisualState.ANALYZING, "Сканирую все кнопки на экране...")
                val svc = AuraAccessibilityService.instance.value
                if (svc != null) {
                    val elements = svc.inspectCurrentScreen()
                    updateStatus(
                        AuraOverlayVisualState.IDLE,
                        "Экран проанализирован: ${elements.size} эл."
                    )
                } else {
                    updateStatus(
                        AuraOverlayVisualState.IDLE,
                        "Включите Accessibility для анализа экрана"
                    )
                }
            }
        }

        topRow.addView(ringFrame)
        topRow.addView(textCol)
        topRow.addView(micBtn)
        topRow.addView(scanBtn)

        // Expanded Interactive Inspector & Quick Control Panel
        val detailsLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (_isExpanded.value) View.VISIBLE else View.GONE
            setPadding(dp(context, 4), dp(context, 10), dp(context, 4), dp(context, 4))
        }

        val summaryTv = TextView(context).apply {
            text = "Инспектор экрана: нажмите 🔍 для поиска всех кнопок"
            setTextColor(0xFF94A3B8.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, 0, 0, dp(context, 6))
        }
        screenSummaryTextView = summaryTv
        detailsLayout.addView(summaryTv)

        // Horizontal scroll of live detected screen buttons (#1, #2, #3...)
        val elementsScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                dp(context, 280),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val elementsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        elementsContainer = elementsRow
        elementsScroll.addView(elementsRow)
        detailsLayout.addView(elementsScroll)

        // Quick cross-app actions row
        val actionsScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                dp(context, 280),
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(context, 8)
            }
        }
        val quickRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val quickActions = listOf(
            "🔎 Анализ экрана" to {
                val svc = AuraAccessibilityService.instance.value
                if (svc != null) {
                    val items = svc.inspectCurrentScreen()
                    updateStatus(AuraOverlayVisualState.IDLE, "Найдено ${items.size} элементов на экране")
                }
            },
            "👆 Нажать #1" to {
                AuraAccessibilityService.instance.value?.clickElementByIndex(1)
                Unit
            },
            "⬇️ Скролл вниз" to {
                AuraAccessibilityService.instance.value?.scrollOrSwipeScreen("DOWN")
                Unit
            },
            "⬆️ Скролл вверх" to {
                AuraAccessibilityService.instance.value?.scrollOrSwipeScreen("UP")
                Unit
            },
            "◀️ Назад" to {
                AuraAccessibilityService.instance.value?.performGlobalSystemAction("BACK")
                Unit
            },
            "🏠 Домой" to {
                AuraAccessibilityService.instance.value?.performGlobalSystemAction("HOME")
                Unit
            },
            "📸 Скриншот" to {
                AuraAccessibilityService.instance.value?.performGlobalSystemAction("SCREENSHOT")
                Unit
            },
            "✨ Открыть Aura" to {
                try {
                    val intent = Intent(context, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    context.startActivity(intent)
                } catch (_: Exception) {
                }
            },
            "✕ Свернуть" to {
                _isExpanded.value = false
                refreshOverlayUi()
            }
        )

        for ((label, action) in quickActions) {
            quickRow.addView(createActionChip(context, label, 0xFF334155.toInt()) { action() })
        }
        actionsScroll.addView(quickRow)
        detailsLayout.addView(actionsScroll)

        expandedPanel = detailsLayout

        mainCard.addView(topRow)
        mainCard.addView(detailsLayout)
        root.addView(mainCard)

        // Dragging + Click to Expand/Collapse on avatar and text area
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragged = false

        val dragListener = View.OnTouchListener { _, event ->
            val params = layoutParams ?: return@OnTouchListener false
            val wm = windowManager ?: return@OnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) {
                        isDragged = true
                        params.x = initialX + dx
                        params.y = initialY + dy
                        try {
                            wm.updateViewLayout(root, params)
                        } catch (_: Exception) {
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragged) {
                        _isExpanded.value = !_isExpanded.value
                        refreshOverlayUi()
                        if (_isExpanded.value) {
                            AuraAccessibilityService.instance.value?.inspectCurrentScreen()
                        }
                    }
                    true
                }
                else -> false
            }
        }

        ringFrame.setOnTouchListener(dragListener)
        textCol.setOnTouchListener(dragListener)

        return root
    }

    private fun createActionChip(
        context: Context,
        text: String,
        bgColor: Int,
        onClick: () -> Unit
    ): TextView {
        return TextView(context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setPadding(dp(context, 10), dp(context, 6), dp(context, 10), dp(context, 6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(context, 6)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(context, 14).toFloat()
                setColor(bgColor)
                setStroke(dp(context, 1), 0x4494A3B8)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun loadCircularAvatarBitmap(context: Context): Bitmap? {
        return try {
            val src = BitmapFactory.decodeResource(
                context.resources,
                R.drawable.img_aura_avatar_1792153220147
            ) ?: return null
            val size = minOf(src.width, src.height)
            val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            }
            val r = size / 2f
            canvas.drawCircle(r, r, r, paint)
            output
        } catch (_: Exception) {
            null
        }
    }

    private fun dp(context: Context, value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }
}
