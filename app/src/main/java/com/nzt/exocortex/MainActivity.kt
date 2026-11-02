package com.nzt.exocortex

import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.provider.Settings
import android.util.TypedValue
import android.view.*
import android.view.animation.LinearInterpolator
import android.widget.*
import kotlin.math.*
import kotlin.random.Random

class MainActivity : Activity() {

    companion object {
        init {
            try {
                System.loadLibrary("nzt_core")
            } catch (_: Throwable) {}
        }
    }

    // Нативные функции C++ ядра (с безопасным фоллбэком, если запускаешь без NDK)
    external fun generatePlanNative(score: Int): String
    external fun getFocusSecondsNative(modeType: Int): Int
    external fun setIdleNative()

    private lateinit var prefs: SharedPreferences
    private lateinit var contentContainer: FrameLayout
    private var activeTimer: CountDownTimer? = null

    // Состояние пользователя
    private var morningScore = 4
    private var completedBlocks = 0
    private var maxBlocks = 4
    private var focusMinutes = 75
    private var restMinutes = 20
    private var currentTimerModeLabel = "ОЖИДАНИЕ СТАРТА"
    private var isTimerRunning = false
    private var zenDopaminePoints = 0

    // База карточек SM-2
    data class FlashcardItem(
        val category: String,
        val question: String,
        val answer: String,
        var intervalDays: Int = 1,
        var ease: Float = 2.5f
    )

    private val flashcards = mutableListOf(
        FlashcardItem("МАТЕМАТИКА • ТЕОРИЯ ЧИСЕЛ", "Малая теорема Ферма:\nчему равно a^(p-1) mod p для простого p?", "a^(p-1) ≡ 1 (mod p),\nесли a не делится на p."),
        FlashcardItem("ГЕОМЕТРИЯ • ОЛИМПИАДЫ", "Теорема Птолемея для вписанного четырехугольника?", "Произведение диагоналей равно сумме произведений противоположных сторон:\nAC · BD = AB · CD + BC · AD"),
        FlashcardItem("КОМБИНАТОРИКА", "Формула включений-исключений для 3 множеств?", "|A∪B∪C| = |A|+|B|+|C| - |A∩B| - |B∩C| - |A∩C| + |A∩B∩C|"),
        FlashcardItem("C++ • СТРУКТУРЫ ДАННЫХ", "Сложность поиска в std::unordered_map (средняя и худшая)?", "В среднем: O(1)\nВ худшем (коллизии хеша): O(N)"),
        FlashcardItem("СПОРТ • ПРОТОКОЛ КИТА БААРА", "Как укрепить связки коленей для взрывного прыжка без травм?", "Изометрическое удержание под нагрузкой 30–45 секунд (3–4 подхода) отдельно от прыжковой тренировки."),
        FlashcardItem("НЕЙРОБИОЛОГИЯ • GAP EFFECT", "Что дают 10 секунд с закрытыми глазами каждые 7 минут учебы?", "Гиппокамп проигрывает новую информацию на скорости x20, мгновенно упаковывая её в долговременную память.")
    )
    private var currentCardIdx = 0
    private var isCardAnswerShown = false

    // Цветовая палитра Cyber-AMOLED
    private val colBg = Color.parseColor("#050508")
    private val colCard = Color.parseColor("#10121A")
    private val colCardStroke = Color.parseColor("#1E2230")
    private val colEmerald = Color.parseColor("#00FF88")
    private val colCyan = Color.parseColor("#00E5FF")
    private val colPurple = Color.parseColor("#9D4EDD")
    private val colAmber = Color.parseColor("#FFB703")
    private val colTextMain = Color.parseColor("#F0F4F8")
    private val colTextMuted = Color.parseColor("#7A869A")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.statusBarColor = colBg
        window.navigationBarColor = Color.parseColor("#0A0C12")

        prefs = getSharedPreferences("nzt_exocortex_prefs", Context.MODE_PRIVATE)
        loadSavedState()

        // Главный корневой контейнер
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colBg)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // Верхний статус-бар приложения (Header)
        root.addView(buildTopHeader())

        // Контейнер для переключения вкладок
        contentContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(contentContainer)

        // Нижняя панель навигации (4 вкладки)
        root.addView(buildBottomNav())

        setContentView(root)
        switchTab(0) // Открываем вкладку ЯДРО по умолчанию
    }

    // ========================================================================
    // ШАПКА И НИЖНЯЯ НАВИГАЦИЯ
    // ========================================================================

    private fun buildTopHeader(): View {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(12))
            setBackgroundColor(colBg)
        }

        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val appTitle = TextView(this).apply {
            text = "NZT // EXOCORTEX"
            setTextColor(colEmerald)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }

        val subTitle = TextView(this).apply {
            text = "СИСТЕМА РАБОТЫ В ПЛЮС • ОТБОЙ 22:00"
            setTextColor(colTextMuted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.MONOSPACE
        }

        titleBox.addView(appTitle)
        titleBox.addView(subTitle)

        // Бейдж текущего режима
        val shieldBtn = TextView(this).apply {
            text = "🛡 ЩИТ ОС"
            setTextColor(colCyan)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = roundedBox(Color.parseColor("#0D1B2A"), colCyan, dp(12), dp(1))
            setOnClickListener {
                vibrate(40)
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        header.addView(titleBox)
        header.addView(shieldBtn)
        return header
    }

    private val navButtons = mutableListOf<TextView>()

    private fun buildBottomNav(): View {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#0A0C12"))
            setPadding(dp(8), dp(10), dp(8), dp(10))
        }

        val tabs = listOf("⚡ ЯДРО", "🎯 ПОТОК", "🧠 ПАМЯТЬ", "🌌 ДЗЕН")
        tabs.forEachIndexed { index, title ->
            val btn = TextView(this).apply {
                text = title
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setPadding(dp(8), dp(12), dp(8), dp(12))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(4), 0, dp(4), 0)
                }
                setOnClickListener {
                    vibrate(30)
                    switchTab(index)
                }
            }
            navButtons.add(btn)
            nav.addView(btn)
        }
        return nav
    }

    private fun switchTab(index: Int) {
        navButtons.forEachIndexed { i, btn ->
            if (i == index) {
                btn.setTextColor(colBg)
                btn.background = roundedBox(colEmerald, colEmerald, dp(14), 0)
            } else {
                btn.setTextColor(colTextMuted)
                btn.background = roundedBox(Color.TRANSPARENT, Color.TRANSPARENT, dp(14), 0)
            }
        }

        contentContainer.removeAllViews()
        when (index) {
            0 -> contentContainer.addView(buildDashboardTab())
            1 -> contentContainer.addView(buildFocusTimerTab())
            2 -> contentContainer.addView(buildMemoryTab())
            3 -> contentContainer.addView(buildZenGameTab())
        }
    }

    // ========================================================================
    // ВКЛАДКА 1: ЯДРО (Утренний чек-ин ЦНС, План дня и Биохимия)
    // ========================================================================

    private fun buildDashboardTab(): View {
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(30))
        }

        // 1. Карточка утреннего чек-ина (1..5)
        val checkInCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = roundedBox(colCard, colCardStroke, dp(20), dp(1))
        }

        checkInCard.addView(TextView(this).apply {
            text = "УТРЕННИЙ ЧЕК-ИН ГОТОВНОСТИ ЦНС"
            setTextColor(colTextMuted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
        })

        checkInCard.addView(TextView(this).apply {
            text = "Как ты себя чувствуешь сегодня?"
            setTextColor(colTextMain)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(14))
        })

        val scoreRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (s in 1..5) {
            val isSelected = (s == morningScore)
            val accent = when {
                s <= 2 -> colAmber
                s == 3 -> colCyan
                else -> colEmerald
            }
            val btn = TextView(this).apply {
                text = "$s"
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setTextColor(if (isSelected) colBg else colTextMain)
                background = roundedBox(
                    if (isSelected) accent else Color.parseColor("#181B26"),
                    if (isSelected) accent else colCardStroke,
                    dp(14), dp(1)
                )
                setPadding(0, dp(14), 0, dp(14))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(4), 0, dp(4), 0)
                }
                setOnClickListener {
                    vibrate(45)
                    updateMorningScore(s)
                    switchTab(0) // Перерисовать экран
                }
            }
            scoreRow.addView(btn)
        }
        checkInCard.addView(scoreRow)
        col.addView(checkInCard)
        col.addView(space(dp(16)))

        // 2. Карточка Протокола на день (Синхронизация Менталки и Спорта)
        val (modeTitle, modeColor, mindText, sportText) = getProtocolDetails(morningScore)
        val protocolCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = roundedBox(colCard, modeColor, dp(20), dp(1))
        }

        protocolCard.addView(TextView(this).apply {
            text = modeTitle
            setTextColor(modeColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        })

        protocolCard.addView(TextView(this).apply {
            text = "\n🧠 КОГНИТИВНЫЙ РЕЖИМ:\n$mindText\n\n🏀 ФИЗИОЛОГИЯ И ПРЫЖОК:\n$sportText"
            setTextColor(colTextMain)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setLineSpacing(dp(4).toFloat(), 1f)
        })

        // Прогресс-бар закрытых учебных блоков
        protocolCard.addView(TextView(this).apply {
            text = "\nВЫПОЛНЕНО БЛОКОВ В ПЛЮС: $completedBlocks / $maxBlocks (по $focusMinutes мин)"
            setTextColor(colCyan)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        })

        col.addView(protocolCard)
        col.addView(space(dp(16)))

        // 3. Интерактивные якоря биохимии
        col.addView(TextView(this).apply {
            text = "ЯКОРЯ БИОХИМИИ И ЭНЕРГИИ (ТАПНИ ДЛЯ ОТМЕТКИ)"
            setTextColor(colTextMuted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            setPadding(dp(4), 0, 0, dp(8))
        })

        col.addView(buildBioToggle("☀ Утренний свет (10 000 люкс)", "Пик кортизола утром + запуск таймера мелатонина к 22:00", "bio_sun"))
        col.addView(space(dp(10)))
        col.addView(buildBioToggle("🥩 Топливо: Белок + Вода + Креатин", "АТФ для взрывного прыжка и нейронов во время сложной математики", "bio_fuel"))
        col.addView(space(dp(10)))
        col.addView(buildBioToggle("🦵 Изометрия связок (Кит Баар)", "3x45 сек удержания под нагрузкой для железобетонных коленей", "bio_tendon"))
        col.addView(space(dp(10)))
        col.addView(buildBioToggle("🎧 Протокол NSDR / Чистый отдых", "15 минут глубокой перезагрузки дофамина без экранов", "bio_nsdr"))

        // Кнопка нового дня
        col.addView(space(dp(20)))
        col.addView(TextView(this).apply {
            text = "🔄 НАЧАТЬ НОВЫЙ ДЕНЬ (СБРОС ДНЕВНЫХ СЧЕТЧИКОВ)"
            gravity = Gravity.CENTER
            setTextColor(colTextMuted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = roundedBox(Color.parseColor("#0D0F17"), colCardStroke, dp(14), dp(1))
            setOnClickListener {
                vibrate(60)
                completedBlocks = 0
                prefs.edit()
                    .putInt("blocks", 0)
                    .putBoolean("bio_sun", false)
                    .putBoolean("bio_fuel", false)
                    .putBoolean("bio_tendon", false)
                    .putBoolean("bio_nsdr", false)
                    .apply()
                switchTab(0)
            }
        })

        scroll.addView(col)
        return scroll
    }

    private fun buildBioToggle(title: String, subtitle: String, key: String): View {
        val isDone = prefs.getBoolean(key, false)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = roundedBox(
                if (isDone) Color.parseColor("#082018") else colCard,
                if (isDone) colEmerald else colCardStroke,
                dp(16), dp(1)
            )
            setOnClickListener {
                vibrate(35)
                prefs.edit().putBoolean(key, !isDone).apply()
                switchTab(0)
            }
        }

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        textCol.addView(TextView(this).apply {
            text = title
            setTextColor(if (isDone) colEmerald else colTextMain)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        })

        textCol.addView(TextView(this).apply {
            text = subtitle
            setTextColor(colTextMuted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        })

        val statusBadge = TextView(this).apply {
            text = if (isDone) "ГОТОВО ✓" else "ОЖИДАЕТ"
            setTextColor(if (isDone) colBg else colTextMuted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = roundedBox(
                if (isDone) colEmerald else Color.parseColor("#181B26"),
                 Color.TRANSPARENT, dp(10), 0
            )
        }

        box.addView(textCol)
        box.addView(statusBadge)
        return box
    }

    // ========================================================================
    // ВКЛАДКА 2: ПОТОК (Неоновый круговой таймер + Gap Effect + Изометрия)
    // ========================================================================

    private fun buildFocusTimerTab(): View {
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(12), dp(20), dp(30))
        }

        // Кастомный Canvas-виджет неонового кольца таймера
        val ringView = NeonTimerRingView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(260), dp(260))
        }
        col.addView(ringView)

        val gapHint = TextView(this).apply {
            text = "Во время потока каждые 7 мин срабатывает сигнал GAP EFFECT (10 сек с закрытыми глазами для записи в гиппокамп)"
            gravity = Gravity.CENTER
            setTextColor(colTextMuted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(12), dp(8), dp(12), dp(16))
        }
        col.addView(gapHint)

        // Кнопка 1: Правило 2 минут
        col.addView(buildActionButton("⚡ 1. ПРАВИЛО «ТОЛЬКО 2 МИНУТЫ»", "Снять стартовое сопротивление и войти в задачу без давления", colCyan) {
            startCustomTimer(120, "РАЗГОН МОЗГА (2 МИН)", colCyan, ringView, isFullFocus = false)
        })
        col.addView(space(dp(12)))

        // Кнопка 2: Глубокий поток (до остановки на пике)
        col.addView(buildActionButton("🔥 2. ГЛУБОКИЙ ПОТОК ($focusMinutes МИН)", "Дофаминовый мусор заблокирован • Авто-стоп на пике", colEmerald) {
            startCustomTimer(focusMinutes * 60, "ГЛУБОКИЙ ФОКУС", colEmerald, ringView, isFullFocus = true)
        })
        col.addView(space(dp(12)))

        // Кнопка 3: Тренажер связок (45 секунд изометрии Кита Баара)
        col.addView(buildActionButton("🦵 3. ИЗОМЕТРИЯ СВЯЗОК И КОЛЕНЕЙ (45 СЕК)", "Удержание полуприседа / резины для синтеза коллагена", colPurple) {
            startCustomTimer(45, "ИЗОМЕТРИЯ СВЯЗОК", colPurple, ringView, isFullFocus = false) {
                prefs.edit().putBoolean("bio_tendon", true).apply()
            }
        })
        col.addView(space(dp(12)))

        // Кнопка сброса таймера
        col.addView(TextView(this).apply {
            text = "⏹ ОСТАНОВИТЬ ТАЙМЕР"
            gravity = Gravity.CENTER
            setTextColor(colAmber)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setOnClickListener {
                vibrate(40)
                activeTimer?.cancel()
                try { setIdleNative() } catch (_: Throwable) {}
                ringView.updateProgress(1f, "00:00", "ОЖИДАНИЕ СТАРТА", colCyan)
            }
        })

        scroll.addView(col)
        return scroll
    }

    private fun startCustomTimer(
        totalSec: Int,
        label: String,
        color: Int,
        ringView: NeonTimerRingView,
        isFullFocus: Boolean,
        onComplete: (() -> Unit)? = null
    ) {
        activeTimer?.cancel()
        vibrate(100)
        try {
            getFocusSecondsNative(if (isFullFocus) 2 else 1)
        } catch (_: Throwable) {}

        var elapsed = 0
        activeTimer = object : CountDownTimer(totalSec * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val rem = (millisUntilFinished / 1000).toInt()
                elapsed++
                val progress = rem.toFloat() / totalSec.toFloat()
                val m = rem / 60
                val s = rem % 60

                // Каждые 7 минут (420 сек) — Gap Effect
                if (isFullFocus && elapsed % 420 == 0 && rem > 20) {
                    vibrate(450)
                    ringView.updateProgress(progress, "10 СЕК", "GAP EFFECT: ЗАКРОЙ ГЛАЗА!", colAmber)
                    return
                }

                ringView.updateProgress(progress, String.format("%02d:%02d", m, s), label, color)
            }

            override fun onFinish() {
                vibrate(700)
                onComplete?.invoke()
                if (isFullFocus) {
                    completedBlocks++
                    prefs.edit().putInt("blocks", completedBlocks).apply()
                    try { getFocusSecondsNative(3) } catch (_: Throwable) {}
                    // Автоматический запуск "Остановки на пике" (Принудительный чистый отдых)
                    startCustomTimer(restMinutes * 60, "СТОП НА ПИКЕ! ОТДЫХ", colAmber, ringView, isFullFocus = false)
                } else {
                    try { setIdleNative() } catch (_: Throwable) {}
                    ringView.updateProgress(1f, "ГОТОВО", "ЦИКЛ ЗАКРЫТ В ПЛЮС ✓", colEmerald)
                }
            }
        }.start()
    }

    // Кастомный Canvas-виджет неонового кольца
    inner class NeonTimerRingView(context: Context) : View(context) {
        private var progress = 1f
        private var timeText = String.format("%02d:00", focusMinutes)
        private var statusText = "ГОТОВ К ПОТОКУ"
        private var ringColor = colEmerald

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(14).toFloat()
            color = Color.parseColor("#161925")
        }

        private val fgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(14).toFloat()
            strokeCap = Paint.Cap.ROUND
            color = ringColor
        }

        private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colTextMain
            textSize = dp(44).toFloat()
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colEmerald
            textSize = dp(12).toFloat()
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        fun updateProgress(p: Float, time: String, status: String, col: Int) {
            progress = p.coerceIn(0f, 1f)
            timeText = time
            statusText = status
            ringColor = col
            fgPaint.color = col
            labelPaint.color = col
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val pad = dp(24).toFloat()
            val rect = RectF(pad, pad, width - pad, height - pad)

            // Фоновое кольцо
            canvas.drawArc(rect, 0f, 360f, false, bgPaint)
            // Активная неоновая дуга
            canvas.drawArc(rect, -90f, 360f * progress, false, fgPaint)

            // Время по центру
            canvas.drawText(timeText, width / 2f, height / 2f + dp(10), timePaint)
            // Статус под временем
            canvas.drawText(statusText, width / 2f, height / 2f + dp(40), labelPaint)
        }
    }

    // ========================================================================
    // ВКЛАДКА 3: ПАМЯТЬ (3D-карточки SM-2 + Добавление своих карточек)
    // ========================================================================

    private fun buildMemoryTab(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(24))
        }

        val cardItem = flashcards[currentCardIdx % flashcards.size]

        // Верхняя плашка статистики памяти
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        topRow.addView(TextView(this).apply {
            text = "КАРТОЧКА ${(currentCardIdx % flashcards.size) + 1} ИЗ ${flashcards.size} • ИНТЕРВАЛ: ${cardItem.intervalDays} ДН."
            setTextColor(colCyan)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })

        val addBtn = TextView(this).apply {
            text = "+ СВОЯ КАРТОЧКА"
            setTextColor(colEmerald)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = roundedBox(Color.parseColor("#082018"), colEmerald, dp(10), dp(1))
            setOnClickListener {
                vibrate(40)
                showAddCardDialog()
            }
        }
        topRow.addView(addBtn)
        col.addView(topRow)
        col.addView(space(dp(16)))

        // Сама интерактивная карточка (тап для переворота)
        val flashcardBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(36), dp(24), dp(36))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            background = roundedBox(
                if (isCardAnswerShown) Color.parseColor("#0B1F24") else colCard,
                if (isCardAnswerShown) colEmerald else colCyan,
                dp(24), dp(2)
            )
            setOnClickListener {
                vibrate(35)
                isCardAnswerShown = !isCardAnswerShown
                switchTab(2)
            }
        }

        flashcardBox.addView(TextView(this).apply {
            text = cardItem.category
            setTextColor(if (isCardAnswerShown) colEmerald else colCyan)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
        })

        flashcardBox.addView(space(dp(20)))

        flashcardBox.addView(TextView(this).apply {
            text = if (isCardAnswerShown) cardItem.answer else cardItem.question
            setTextColor(colTextMain)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setLineSpacing(dp(6).toFloat(), 1f)
        })

        flashcardBox.addView(space(dp(28)))

        flashcardBox.addView(TextView(this).apply {
            text = if (isCardAnswerShown) "ОЦЕНИ СКОРОСТЬ ВСПОМИНАНИЯ НИЖЕ ↓" else "👆 ТАПНИ ПО КАРТОЧКЕ, ЧТОБЫ ПРОВЕРИТЬ ОТВЕТ"
            setTextColor(colTextMuted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
        })

        col.addView(flashcardBox)
        col.addView(space(dp(16)))

        // Кнопки алгоритма SM-2
        val gradeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val grades = listOf(
            Triple("ТЯЖЕЛО (1Д)", colAmber, 1),
            Triple("НОРМ (x2)", colCyan, 3),
            Triple("МГНОВЕННО (x3)", colEmerald, 5)
        )

        for ((label, color, grade) in grades) {
            val btn = TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                setTextColor(colBg)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setPadding(dp(8), dp(16), dp(8), dp(16))
                background = roundedBox(color, color, dp(14), 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(4), 0, dp(4), 0)
                }
                setOnClickListener {
                    vibrate(45)
                    if (grade < 3) {
                        cardItem.intervalDays = 1
                    } else {
                        cardItem.intervalDays = (cardItem.intervalDays * (if (grade == 5) 3 else 2)).coerceAtMost(90)
                    }
                    isCardAnswerShown = false
                    currentCardIdx = (currentCardIdx + 1) % flashcards.size
                    switchTab(2)
                }
            }
            gradeRow.addView(btn)
        }

        col.addView(gradeRow)
        return col
    }

    private fun showAddCardDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
        }
        val catInput = EditText(this).apply { hint = "Категория (напр. МАТЕМАТИКА / ПОЛЬСКИЙ)" }
        val qInput = EditText(this).apply { hint = "Вопрос / Слово / Теорема" }
        val aInput = EditText(this).apply { hint = "Ответ / Перевод / Формула" }
        layout.addView(catInput)
        layout.addView(qInput)
        layout.addView(aInput)

        AlertDialog.Builder(this)
            .setTitle("Добавить в Сверхпамять")
            .setView(layout)
            .setPositiveButton("Сохранить") { _, _ ->
                val c = catInput.text.toString().ifBlank { "ЛИЧНАЯ БАЗА" }
                val q = qInput.text.toString()
                val a = aInput.text.toString()
                if (q.isNotBlank() && a.isNotBlank()) {
                    flashcards.add(FlashcardItem(c.uppercase(), q, a))
                    currentCardIdx = flashcards.size - 1
                    isCardAnswerShown = false
                    switchTab(2)
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ========================================================================
    // ВКЛАДКА 4: АВТОРСКАЯ МИНИ-ИГРА "NEURO-PULSE" (Антистресс-тыкалка для отдыха)
    // ========================================================================

    private fun buildZenGameTab(): View {
        val frame = FrameLayout(this)

        // Интерактивный игровой холст на 60 FPS
        val zenCanvas = NeuroPulseZenView(this)
        frame.addView(zenCanvas)

        // Плавающая плашка сверху с инструкцией и счетчиком чистого дофамина
        val hud = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = roundedBox(Color.parseColor("#CC0A0C14"), colCardStroke, dp(16), dp(1))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            ).apply { setMargins(dp(16), dp(12), dp(16), 0) }
        }

        hud.addView(TextView(this).apply {
            text = "🌌 NEURO-PULSE // СИНХРОНИЗАЦИЯ И ЧИСТЫЙ ОТДЫХ"
            setTextColor(colCyan)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        })

        hud.addView(TextView(this).apply {
            text = "Тапай по био-сферам в ритме дыхательного круга в центре. Это переводит зрительную кору в рассеянный режим, снимает спазм глаз и ускоряет очистку мозга от глутамата."
            setTextColor(colTextMuted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        })

        frame.addView(hud)
        return frame
    }

    // Движок антистресс-игры Neuro-Pulse
    inner class NeuroPulseZenView(context: Context) : View(context) {

        inner class Orb(
            var x: Float,
            var y: Float,
            var vx: Float,
            var vy: Float,
            var radius: Float,
            var color: Int
        )

        inner class Ripple(
            var x: Float,
            var y: Float,
            var radius: Float,
            var maxRadius: Float,
            var alpha: Int,
            var color: Int
        )

        inner class Spark(
            var x: Float,
            var y: Float,
            var vx: Float,
            var vy: Float,
            var alpha: Int,
            var color: Int
        )

        private val orbs = mutableListOf<Orb>()
        private val ripples = mutableListOf<Ripple>()
        private val sparks = mutableListOf<Spark>()
        private val neonColors = listOf(colEmerald, colCyan, colPurple, colAmber)

        private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1).toFloat()
        }
        private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(3).toFloat()
        }
        private val breathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
            color = Color.parseColor("#3300E5FF")
        }
        private val scorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colEmerald
            textSize = dp(14).toFloat()
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        private var breathPhase = 0f
        private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 16L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                updatePhysics()
                invalidate()
            }
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (orbs.isEmpty() && w > 0 && h > 0) {
                for (i in 0 until 14) {
                    spawnOrb(Random.nextFloat() * w, Random.nextFloat() * h)
                }
                animator.start()
            }
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            animator.cancel()
        }

        private fun spawnOrb(x: Float, y: Float) {
            val col = neonColors[Random.nextInt(neonColors.size)]
            orbs.add(
                Orb(
                    x = x,
                    y = y,
                    vx = (Random.nextFloat() - 0.5f) * 3.2f,
                    vy = (Random.nextFloat() - 0.5f) * 3.2f,
                    radius = dp(Random.nextInt(18, 32)).toFloat(),
                    color = col
                )
            )
        }

        private fun updatePhysics() {
            if (width <= 0 || height <= 0) return
            breathPhase += 0.02f

            // Движение био-сфер
            for (orb in orbs) {
                orb.x += orb.vx
                orb.y += orb.vy
                if (orb.x < orb.radius || orb.x > width - orb.radius) orb.vx *= -1f
                if (orb.y < dp(110) + orb.radius || orb.y > height - orb.radius) orb.vy *= -1f
            }

            // Расширение неоновых волн от тапов
            val rIter = ripples.iterator()
            while (rIter.hasNext()) {
                val r = rIter.next()
                r.radius += dp(5).toFloat()
                r.alpha = (r.alpha - 9).coerceAtLeast(0)
                if (r.alpha <= 0 || r.radius >= r.maxRadius) rIter.remove()
            }

            // Разлет светящихся искр
            val sIter = sparks.iterator()
            while (sIter.hasNext()) {
                val s = sIter.next()
                s.x += s.vx
                s.y += s.vy
                s.vy += 0.12f // Легкая невесомая гравитация
                s.alpha = (s.alpha - 10).coerceAtLeast(0)
                if (s.alpha <= 0) sIter.remove()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f + dp(30)

            // 1. Центральный дыхательный круг-метроном (Вдох 4с - Пауза - Выдох 4с)
            val breathScale = (sin(breathPhase) + 1f) / 2f // 0..1
            val breathRadius = dp(55) + breathScale * dp(75)
            canvas.drawCircle(cx, cy, breathRadius, breathPaint)

            val breathLabel = when {
                cos(breathPhase) > 0.3f -> "МЕДЛЕННЫЙ ВДОХ НОСОМ..."
                cos(breathPhase) < -0.3f -> "ПЛАВНЫЙ ВЫДОХ (СБРОС КОРТИЗОЛА)..."
                else -> "ПАУЗА (ФОКУС ВДАЛЬ)..."
            }
            canvas.drawText(breathLabel, cx, cy + dp(5), scorePaint)
            canvas.drawText(
                "НЕЙРО-СИНХРОНИЗАЦИЯ: $zenDopaminePoints ИМПУЛЬСОВ",
                cx,
                height - dp(24).toFloat(),
                scorePaint
            )

            // 2. Рисуем нейронные связи между близкими сферами (эффект созвездий)
            for (i in 0 until orbs.size) {
                for (j in i + 1 until orbs.size) {
                    val a = orbs[i]
                    val b = orbs[j]
                    val dist = hypot(a.x - b.x, a.y - b.y)
                    val maxDist = dp(145).toFloat()
                    if (dist < maxDist) {
                        val alpha = ((1f - dist / maxDist) * 110).toInt()
                        linePaint.color = a.color
                        linePaint.alpha = alpha
                        canvas.drawLine(a.x, a.y, b.x, b.y, linePaint)
                    }
                }
            }

            // 3. Рисуем сами био-сферы с мягким свечением
            for (orb in orbs) {
                orbPaint.color = orb.color
                orbPaint.alpha = 50
                canvas.drawCircle(orb.x, orb.y, orb.radius * 1.45f, orbPaint)

                orbPaint.alpha = 220
                canvas.drawCircle(orb.x, orb.y, orb.radius, orbPaint)
            }

            // 4. Рисуем расходящиеся ударные волны
            for (r in ripples) {
                ripplePaint.color = r.color
                ripplePaint.alpha = r.alpha
                canvas.drawCircle(r.x, r.y, r.radius, ripplePaint)
            }

            // 5. Рисуем искры
            for (s in sparks) {
                orbPaint.color = s.color
                orbPaint.alpha = s.alpha
                canvas.drawCircle(s.x, s.y, dp(4).toFloat(), orbPaint)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
                val tx = event.x
                val ty = event.y

                // Проверяем, лопнул ли пользователь био-сферу
                var hitColor = colCyan
                var hit = false
                for (orb in orbs) {
                    if (hypot(tx - orb.x, ty - orb.y) <= orb.radius * 1.6f) {
                        hit = true
                        hitColor = orb.color
                        zenDopaminePoints++
                        vibrate(25) // Приятный тактильный микро-отклик

                        // Рождаем 12 светящихся искр
                        for (k in 0 until 12) {
                            val angle = Random.nextFloat() * (2 * Math.PI.toFloat())
                            val speed = Random.nextFloat() * 9f + 2f
                            sparks.add(Spark(orb.x, orb.y, cos(angle) * speed, sin(angle) * speed, 255, orb.color))
                        }

                        // Перемещаем сферу в новое место
                        orb.x = Random.nextFloat() * (width - dp(60)) + dp(30)
                        orb.y = Random.nextFloat() * (height - dp(200)) + dp(130)
                        orb.color = neonColors[Random.nextInt(neonColors.size)]
                        break
                    }
                }

                // Пускаем волну от любого касания
                if (event.action == MotionEvent.ACTION_DOWN || hit) {
                    ripples.add(Ripple(tx, ty, dp(10).toFloat(), dp(130).toFloat(), 240, hitColor))
                }
                return true
            }
            return super.onTouchEvent(event)
        }
    }

    // ========================================================================
    // ВСПОМОГАТЕЛЬНАЯ ЛОГИКА И UI-КОМПОНЕНТЫ
    // ========================================================================

    private fun updateMorningScore(score: Int) {
        morningScore = score.coerceIn(1, 5)
        when {
            morningScore <= 2 -> { focusMinutes = 15; restMinutes = 15; maxBlocks = 2 }
            morningScore == 3 -> { focusMinutes = 45; restMinutes = 15; maxBlocks = 3 }
            else -> { focusMinutes = 75; restMinutes = 20; maxBlocks = 4 }
        }
        prefs.edit().putInt("score", morningScore).apply()
        try { generatePlanNative(morningScore) } catch (_: Throwable) {}
    }

    private fun loadSavedState() {
        morningScore = prefs.getInt("score", 4)
        completedBlocks = prefs.getInt("blocks", 0)
        updateMorningScore(morningScore)
    }

    data class Quad(val title: String, val color: Int, val mind: String, val sport: String)

    private fun getProtocolDetails(score: Int): Quad {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        if (hour >= 22 || hour < 6) {
            return Quad(
                "🌙 КОМЕНДАНТСКИЙ ЧАС ДЛЯ ДОФАМИНА (22:00+)",
                colPurple,
                "Учеба в кредит у сна запрещена. Сейчас твой мозг переносит решенные за день теоремы из гиппокампа в кору.",
                "Пик выработки гормона роста (соматотропина). Восстановление связок и ЦНС."
            )
        }
        return when {
            score <= 2 -> Quad(
                "🛡 РЕЖИМ: АНТИ-ВЫГОРАНИЕ (ПРОТОКОЛ МИНИМУМА)",
                colAmber,
                "Твоя задача сегодня — не геройствовать, а сохранить ритм. Делаем 1–2 легких подхода по 15 минут без малейшего чувства вины.",
                "ЦНС утомлена! Ударные прыжки сегодня запрещены (бережем колени). Сделай только 3x45 сек изометрии связок и легкую растяжку."
            )
            score == 3 -> Quad(
                "⚖ РЕЖИМ: БАЛАНС В ПЛЮС (СТАБИЛЬНЫЙ ТОНУС)",
                colCyan,
                "Отличный рабочий день. 3 блока по 45 минут. Главное правило — останавливаться строго по таймеру на пике, пока голова еще свежая.",
                "Работа над техникой, дриблингом, броском или аэробный бег на низком пульсе (до 135–140 уд/мин) + изометрия связок."
            )
            else -> Quad(
                "🔥 РЕЖИМ: ХИЩНИК (ПИКОВАЯ ГОТОВНОСТЬ ЦНС 100%)",
                colEmerald,
                "Кристальная ясность («Третий глаз»). Штурмуем самые тяжелые олимпиадные задачи и код. Сессии по 75 минут с микро-паузами Gap Effect.",
                "ЦНС полностью заряжена! Зеленый свет на взрывную плиометрику, максимальные вертикальные прыжки и данки."
            )
        }
    }

    private fun buildActionButton(title: String, subtitle: String, accentColor: Int, onClick: () -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = roundedBox(colCard, accentColor, dp(18), dp(1))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener { onClick() }
        }
        box.addView(TextView(this).apply {
            text = title
            setTextColor(accentColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        })
        box.addView(TextView(this).apply {
            text = subtitle
            setTextColor(colTextMuted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, dp(4), 0, 0)
        })
        return box
    }

    private fun roundedBox(fillColor: Int, strokeColor: Int, radiusPx: Int, strokeWidthPx: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx.toFloat()
            setColor(fillColor)
            if (strokeWidthPx > 0) setStroke(strokeWidthPx, strokeColor)
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private fun space(heightPx: Int) = Space(this).apply { minimumHeight = heightPx }

    private fun vibrate(ms: Long) {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(ms)
            }
        } catch (_: Throwable) {}
    }
}