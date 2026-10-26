package com.example.zunelauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.example.zunelauncher.databinding.ActivityMainBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Home screen. Three pivot pages:  0 assistant  |  1 apps  |  2 frequent
 *
 * The list item closest to a "focus line" (36% down the screen) is the highlighted app. Its icon-derived
 * colour is pushed to [ZuneOledWallpaperView] and to the assistant accents, so the whole UI re-tints
 * as you scroll.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val appRepo by lazy { AppRepository(applicationContext) }
    private val feeds = FeedRepository()
    private val prefs by lazy { getSharedPreferences("launcher", MODE_PRIVATE) }

    private var allApps: List<AppEntry> = emptyList()
    private var reloadJob: Job? = null
    private var accent = ColorExtractor.DEFAULT_ACCENT
    private var bottomInset = 0

    private var weather: Weather? = null
    private var markets: StockSnapshot? = null

    private val allAdapter = AppAdapter(::launchApp, ::openAppInfo)
    private val frequentAdapter = AppAdapter(::launchApp, ::openAppInfo)

    private val lists = arrayOfNulls<RecyclerView>(PAGE_COUNT)
    private val emptyViews = arrayOfNulls<View>(PAGE_COUNT)
    private val focusColors = IntArray(PAGE_COUNT) { ColorExtractor.DEFAULT_ACCENT }
    private var assistant: AssistantViews? = null

    private class AssistantViews(root: View) {
        val scroll = root as NestedScrollView
        val date: TextView = root.findViewById(R.id.tvDate)
        val greeting: TextView = root.findViewById(R.id.tvGreeting)
        val name: TextView = root.findViewById(R.id.tvName)
        val weatherLabel: TextView = root.findViewById(R.id.tvWeatherLabel)
        val temp: TextView = root.findViewById(R.id.tvTemp)
        val weatherDetail: TextView = root.findViewById(R.id.tvWeatherDetail)
        val marketsLabel: TextView = root.findViewById(R.id.tvMarketsLabel)
        val stocks: LinearLayout = root.findViewById(R.id.stocksContainer)
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = reloadApps()
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            binding.pivotHeader.updatePadding(top = bars.top)
            bottomInset = bars.bottom
            applyBottomInset()
            insets
        }

        setUpPivot()

        // A launcher must never "finish": Back just returns to the first pivot page.
        onBackPressedDispatcher.addCallback(this) { binding.pager.setCurrentItem(0, true) }

        // Keep the app list fresh when apps are installed / removed / updated.
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(this, packageReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        reloadApps()
        startLiveUpdates()
    }

    override fun onResume() {
        super.onResume()
        refreshFrequent() // launch counts may have changed while another app was on screen
        renderAssistant()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) { // Home pressed while already on the launcher
            lists.forEach { it?.smoothScrollToPosition(0) }
            binding.pager.setCurrentItem(0, true)
        }
    }

    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ pivot setup

    private fun setUpPivot() {
        binding.pivotHeader.setTitles(listOf("assistant", "apps", "frequent")) { page ->
            binding.pager.setCurrentItem(page, true)
        }

        binding.pager.apply {
            adapter = PivotPageAdapter(
                listOf(R.layout.page_assistant, R.layout.page_list, R.layout.page_list),
                ::onPageBound
            )
            offscreenPageLimit = PAGE_COUNT - 1
            (getChildAt(0) as? RecyclerView)?.overScrollMode = View.OVER_SCROLL_NEVER
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                    binding.pivotHeader.onPageScrolled(position, positionOffset)
                    binding.wallpaper.setScrollProgress((position + positionOffset) / (PAGE_COUNT - 1))
                }

                override fun onPageSelected(position: Int) {
                    setAccent(if (position == 0) ColorExtractor.DEFAULT_ACCENT else focusColors[position])
                }
            })
        }
    }

    private fun onPageBound(page: Int, view: View) {
        if (page == 0) {
            assistant = AssistantViews(view).also { a ->
                a.greeting.setOnLongClickListener { promptForName(); true }
                a.name.setOnLongClickListener { promptForName(); true }
            }
            applyBottomInset()
            renderAssistant()
        } else {
            bindList(page, view)
        }
    }

    private fun bindList(page: Int, view: View) {
        val rv = view.findViewById<RecyclerView>(R.id.list)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = if (page == 1) allAdapter else frequentAdapter
        rv.itemAnimator = null
        rv.clipToPadding = false
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = trackFocus(page)
        })
        // Also re-evaluate after layout passes (first fill, adapter updates, rotation, insets).
        rv.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> trackFocus(page) }
        lists[page] = rv
        emptyViews[page] = view.findViewById(R.id.empty)
        applyBottomInset()
        updateEmpty()
    }

    private fun applyBottomInset() {
        lists.forEach { it?.updatePadding(top = dp(8), bottom = bottomInset + dp(32)) }
        assistant?.scroll?.updatePadding(bottom = bottomInset)
    }

    // ------------------------------------------------------------------ apps + focus colour

    private fun reloadApps() {
        reloadJob?.cancel()
        reloadJob = lifecycleScope.launch {
            allApps = appRepo.loadInstalledApps()
            allAdapter.submitList(allApps)
            refreshFrequent()
        }
    }

    private fun refreshFrequent() {
        frequentAdapter.submitList(appRepo.frequent(allApps)) { updateEmpty() }
    }

    private fun updateEmpty() {
        emptyViews[2]?.isVisible = frequentAdapter.currentList.isEmpty()
    }

    /** Dims/shrinks rows away from the focus line and tints the row on it with its app colour. */
    private fun trackFocus(page: Int) {
        val rv = lists[page] ?: return
        val h = rv.height
        if (h == 0 || rv.childCount == 0) return
        val focusY = h * 0.36f

        var best: View? = null
        var bestDist = Float.MAX_VALUE
        for (i in 0 until rv.childCount) {
            val c = rv.getChildAt(i)
            val d = abs((c.top + c.bottom) / 2f - focusY)
            if (d < bestDist) { bestDist = d; best = c }
        }
        for (i in 0 until rv.childCount) {
            val c = rv.getChildAt(i) as TextView
            val t = (abs((c.top + c.bottom) / 2f - focusY) / (h * 0.65f)).coerceIn(0f, 1f)
            val s = 1f - 0.16f * t
            c.pivotX = 0f
            c.pivotY = c.height / 2f
            c.scaleX = s
            c.scaleY = s
            c.alpha = 1f - 0.65f * t
            c.setTextColor(if (c === best) (c.tag as? AppEntry)?.color ?: Color.WHITE else Color.WHITE)
        }

        (best?.tag as? AppEntry)?.let { entry ->
            focusColors[page] = entry.color
            if (binding.pager.currentItem == page) setAccent(entry.color)
        }
    }

    private fun setAccent(color: Int) {
        if (color == accent) return
        accent = color
        binding.wallpaper.setGlowColor(color)
        renderAssistant()
    }

    // ------------------------------------------------------------------ launching

    private fun launchApp(app: AppEntry, source: View) {
        binding.wallpaper.pulse()
        if (!appRepo.launch(this, app, source)) {
            Toast.makeText(this, R.string.launch_failed, Toast.LENGTH_SHORT).show()
            reloadApps() // probably uninstalled/disabled: refresh the list
        }
    }

    private fun openAppInfo(app: AppEntry) {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    // ------------------------------------------------------------------ assistant

    private fun startLiveUpdates() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // greeting / date re-render on the minute
                launch {
                    while (true) {
                        renderAssistant()
                        delay(60_000L - System.currentTimeMillis() % 60_000L)
                    }
                }
                // weather + markets (repository caches, so this is cheap on repeated resumes)
                launch {
                    while (true) {
                        coroutineScope {
                            val w = async { feeds.weather() }
                            val m = async { feeds.quotes() }
                            weather = w.await()
                            markets = m.await()
                        }
                        renderAssistant()
                        delay(FEED_REFRESH_MS)
                    }
                }
            }
        }
    }

    private fun renderAssistant() {
        val a = assistant ?: return
        val now = LocalDateTime.now()

        a.date.text = now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault()))
        a.greeting.setText(
            when (now.hour) {
                in 5..11 -> R.string.greeting_morning
                in 12..16 -> R.string.greeting_afternoon
                else -> R.string.greeting_evening
            }
        )
        a.name.text = userName()
        a.date.setTextColor(accent)
        a.name.setTextColor(accent)

        val w = weather
        a.weatherLabel.text = getString(R.string.label_weather) + if (w?.isLive == false) "  ·  simulated" else ""
        a.temp.text = if (w != null) "${w.tempC}°" else "--°"
        a.weatherDetail.text = if (w != null) {
            "${w.condition.lowercase()}  ·  h ${w.highC}°  l ${w.lowC}°  ·  ${w.city.lowercase()}"
        } else getString(R.string.loading)

        val m = markets
        a.marketsLabel.text = getString(R.string.label_markets) + if (m?.isLive == false) "  ·  simulated" else ""
        a.stocks.removeAllViews()
        m?.items?.forEach { a.stocks.addView(stockRow(it)) }
    }

    private fun stockRow(q: StockQuote): View {
        val up = q.changePercent >= 0
        fun cell(text: String, widthDp: Int, color: Int, family: String) = TextView(this).apply {
            this.text = text
            setTextColor(color)
            textSize = 22f
            typeface = android.graphics.Typeface.create(family, android.graphics.Typeface.NORMAL)
            fontFeatureSettings = "tnum"
            layoutParams = LinearLayout.LayoutParams(
                if (widthDp > 0) dp(widthDp) else ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(24); topMargin = dp(6) }
            addView(cell(q.symbol, 96, Color.WHITE, "sans-serif-medium"))
            addView(cell(String.format(Locale.US, "%.2f", q.price), 120, 0xFFCFCFCF.toInt(), "sans-serif-light"))
            addView(
                cell(
                    (if (up) "▲ " else "▼ ") + String.format(Locale.US, "%.2f%%", abs(q.changePercent)),
                    0, if (up) COLOR_UP else COLOR_DOWN, "sans-serif-medium"
                )
            )
        }
    }

    private fun userName() = prefs.getString(KEY_NAME, null) ?: DEFAULT_NAME

    private fun promptForName() {
        val input = EditText(this).apply {
            setText(userName())
            setSingleLine()
            setSelectAllOnFocus(true)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.name_dialog_title)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                prefs.edit { putString(KEY_NAME, input.text.toString().trim().ifEmpty { DEFAULT_NAME }) }
                renderAssistant()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private companion object {
        const val PAGE_COUNT = 3
        const val FEED_REFRESH_MS = 30 * 60_000L
        const val KEY_NAME = "user_name"
        const val DEFAULT_NAME = "User"
        val COLOR_UP = 0xFF00E676.toInt()
        val COLOR_DOWN = 0xFFFF1744.toInt()
    }
}
