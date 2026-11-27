package ai.aneesiqbal.rozan.airplay.android

import ai.aneesiqbal.rozan.airplay.android.store.AirPlayFiles
import ai.aneesiqbal.rozan.airplay.android.store.CachedProperties
import ai.aneesiqbal.rozan.airplay.android.store.openSharedProperties
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.app.LocaleManager
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.LocaleList
import android.os.Looper
import androidx.annotation.RequiresApi

/**
 * The language of the app's screens: the system language by default, or one of [Language] chosen in
 * the launcher's settings. While it follows the system, the first of the system's languages the app
 * is translated into is used, and English when there is none.
 *
 * From API 33 the choice is Android's per-app language ([LocaleManager]): the system applies it to
 * every process of the app, recreates the activities when it changes, and also offers it in its own
 * per-app language settings (the launcher's `android:localeConfig`). Before API 33 Android has no
 * per-app language, so the choice is a small file shared by the app's processes, like
 * [AirPlaySettings], and the app applies it itself:
 *
 * - every Activity and Service, and the Application, pass their base context through [wrap] in
 *   `attachBaseContext`;
 * - the Application calls [install], which makes the choice the process's default locale and
 *   recreates the process's activities when it changes;
 * - a context that outlives a change (the application's, a running service's) is passed through
 *   [wrap] again whenever text is read from it, and [watch] says when to read it again.
 *
 * Main thread only.
 */
public object AppLanguage {
    /** A language the app is translated into, with its name in that language (never translated). */
    public enum class Language(
        /** BCP 47 tag; also what is stored for the choice. */
        public val tag: String,
        /** The language's name in itself, as the language setting lists it. */
        public val nativeName: String,
    ) {
        /** English, the language of the untranslated resources. */
        ENGLISH("en", "English"),

        /** Chinese in Simplified characters. */
        CHINESE_SIMPLIFIED("zh-Hans", "简体中文"),

        /** French. */
        FRENCH("fr", "Français"),

        /** Italian. */
        ITALIAN("it", "Italiano"),

        /** Japanese. */
        JAPANESE("ja", "日本語"),

        /** Brazilian Portuguese; also what Portuguese from other regions gets. */
        PORTUGUESE_BRAZIL("pt-BR", "Português (Brasil)"),

        /** Russian. */
        RUSSIAN("ru", "Русский"),

        /** Spanish. */
        SPANISH("es", "Español"),
    }

    private const val TAG = "AppLanguage"
    private const val KEY_LANGUAGE = "language"

    private val main = Handler(Looper.getMainLooper())

    /** [watch] listeners of this process, told at once when [choose] runs here (before API 33). */
    private val localListeners = LinkedHashSet<Runnable>()

    @Volatile private var shared: CachedProperties? = null

    /** The chosen language, or null while the app follows the system language. */
    public fun chosen(context: Context): Language? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Api33.chosen(context)
        } else {
            LanguageTags.fromStored(store(context).get(KEY_LANGUAGE))
        }

    /**
     * Chooses [language], or the system language for null. The activities of every process of the app
     * are recreated in it: by the system from API 33, by [install] before.
     */
    public fun choose(
        context: Context,
        language: Language?,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Api33.choose(context, language)
            return
        }
        store(context).set(KEY_LANGUAGE, LanguageTags.toStored(language))
        // This process at once; the others when their file watcher reports the write.
        for (listener in localListeners.toList()) main.post(listener)
    }

    /** The language the app shows while it follows the system language. */
    public fun systemLanguage(context: Context): Language =
        LanguageTags.shown(primaryTag(inSystemLanguage(context).resources.configuration.locales))

    /**
     * [context] if its resources are in the app language, else a copy of it that is. For
     * `attachBaseContext` of every Activity, Service and the Application, and for reading text from a
     * context created before the latest change. From API 33 the system does this, and it returns [context].
     */
    public fun wrap(context: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return context
        val chosen = chosen(context)
        if (shows(context, chosen)) return context
        val locales = if (chosen == null) systemLocales(context) else LocaleList.forLanguageTags(chosen.tag)
        return configured(context, locales)
    }

    /**
     * [context] if its resources are in the system language, else a copy of it that is: for text that
     * must read as the system's own screens show it, such as a label the system reads from the manifest.
     */
    public fun inSystemLanguage(context: Context): Context {
        val system = systemLocales(context)
        val current = context.resources.configuration.locales
        return if (sameLocales(current, system)) context else configured(context, system)
    }

    /**
     * Before API 33, applies the app language to this process: makes it the default locale (dates, sorting,
     * and the glyphs of Chinese and Japanese text follow it, as they do from API 33), keeps it so across
     * configuration changes, and recreates the activities of this process when the language changes. Call it
     * from `Application.onCreate` of every process that shows text. From API 33 it does nothing.
     */
    public fun install(application: Application) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        val activities = ActivityTracker()
        application.registerActivityLifecycleCallbacks(activities)
        setProcessDefault(application)
        application.registerComponentCallbacks(
            object : ComponentCallbacks {
                // The system resets the default locale to the application's on every configuration change.
                override fun onConfigurationChanged(newConfig: Configuration) = setProcessDefault(application)

                @Deprecated("Deprecated in the platform, still abstract; there is nothing to release.")
                override fun onLowMemory() = Unit
            },
        )
        watch(application) {
            setProcessDefault(application)
            val chosen = chosen(application)
            for (activity in activities.live()) {
                if (!activity.isFinishing && !shows(activity, chosen)) activity.recreate()
            }
        }
    }

    /**
     * Runs [onChanged] on the main thread after the app language may have changed, in this process or
     * another one (before API 33), or after any configuration change (from API 33, when the system
     * applies a new language that way). Returns a handle that stops it.
     */
    public fun watch(
        context: Context,
        onChanged: Runnable,
    ): AutoCloseable {
        val appContext = context.applicationContext ?: context
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val callbacks =
                object : ComponentCallbacks {
                    override fun onConfigurationChanged(newConfig: Configuration) = onChanged.run()

                    @Deprecated("Deprecated in the platform, still abstract; there is nothing to release.")
                    override fun onLowMemory() = Unit
                }
            appContext.registerComponentCallbacks(callbacks)
            return AutoCloseable { appContext.unregisterComponentCallbacks(callbacks) }
        }
        // Loaded now, so the file watcher refreshes this process's copy before onChanged runs.
        store(appContext)
        localListeners += onChanged
        AirPlayFiles.watch(appContext, AirPlayFiles.LANGUAGE, onChanged)
        return AutoCloseable {
            localListeners -= onChanged
            AirPlayFiles.unwatch(AirPlayFiles.LANGUAGE, onChanged)
        }
    }

    /** Whether [context]'s resources show [chosen] (null: the system language). */
    private fun shows(
        context: Context,
        chosen: Language?,
    ): Boolean {
        val locales = context.resources.configuration.locales
        return if (chosen == null) {
            sameLocales(locales, systemLocales(context))
        } else {
            LanguageTags.match(primaryTag(locales)) == chosen
        }
    }

    private fun setProcessDefault(context: Context) {
        val chosen = chosen(context)
        LocaleList.setDefault(if (chosen == null) systemLocales(context) else LocaleList.forLanguageTags(chosen.tag))
    }

    private fun systemLocales(context: Context): LocaleList =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Api33.systemLocales(context)
        } else {
            // The system's own resources never carry this app's choice.
            Resources
                .getSystem()
                .configuration.locales
        }

    // Lint cannot see from a library that the apps turn off language splits for app bundles
    // (rozan.android.application), so every language this picks from is installed.
    @SuppressLint("AppBundleLocaleChanges")
    private fun configured(
        context: Context,
        locales: LocaleList,
    ): Context = context.createConfigurationContext(Configuration().apply { setLocales(locales) })

    /**
     * Equal as sets: the resources move the locale they resolve to to the front of the list, so a
     * context that follows the system may list the system's locales in another order.
     */
    private fun sameLocales(
        a: LocaleList,
        b: LocaleList,
    ): Boolean = a.size() == b.size() && (0 until a.size()).all { b.indexOf(a[it]) >= 0 }

    private fun primaryTag(locales: LocaleList): String = if (locales.isEmpty) "" else locales[0].toLanguageTag()

    private fun store(context: Context): CachedProperties =
        shared ?: synchronized(this) {
            shared ?: openSharedProperties(context, AirPlayFiles.LANGUAGE, "app-language", TAG).also { shared = it }
        }

    /** The activities of this process between create and destroy. */
    private class ActivityTracker : Application.ActivityLifecycleCallbacks {
        private val activities = LinkedHashSet<Activity>()

        fun live(): List<Activity> = activities.toList()

        override fun onActivityCreated(
            activity: Activity,
            savedInstanceState: Bundle?,
        ) {
            activities += activity
        }

        override fun onActivityDestroyed(activity: Activity) {
            activities -= activity
        }

        override fun onActivityStarted(activity: Activity) = Unit

        override fun onActivityResumed(activity: Activity) = Unit

        override fun onActivityPaused(activity: Activity) = Unit

        override fun onActivityStopped(activity: Activity) = Unit

        override fun onActivitySaveInstanceState(
            activity: Activity,
            outState: Bundle,
        ) = Unit
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private object Api33 {
        fun chosen(context: Context): Language? {
            val locales = manager(context).applicationLocales
            return if (locales.isEmpty) null else LanguageTags.match(locales[0].toLanguageTag())
        }

        fun choose(
            context: Context,
            language: Language?,
        ) {
            manager(context).applicationLocales =
                if (language == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(language.tag)
        }

        fun systemLocales(context: Context): LocaleList = manager(context).systemLocales

        private fun manager(context: Context): LocaleManager = context.getSystemService(LocaleManager::class.java)
    }
}
