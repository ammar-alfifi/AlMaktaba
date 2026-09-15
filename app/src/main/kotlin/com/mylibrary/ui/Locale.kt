package com.mylibrary.ui

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.unit.LayoutDirection
import com.mylibrary.core.domain.model.AppLanguage
import java.util.Locale

/**
 * Applies an in-app language choice to a [Context].
 *
 * This is how MyLibrary changes language without a restart and without the AppCompat delegate. A
 * configuration-scoped context is created for the chosen locale, and the Compose tree is then given
 * that context — so `stringResource`, resource-backed icons and `LocalLayoutDirection` all resolve
 * against the chosen language while the rest of the process is untouched.
 *
 * The consequences are deliberate:
 *
 *  - **The app's own UI follows the choice immediately**, and Arabic gets RTL because
 *    `setLayoutDirection` is applied to the configuration as well as `setLocale`.
 *  - **System-rendered dialogs** (a date picker drawn by the platform, the share sheet) still follow
 *    the *device* language, because they are rendered by a different process that knows nothing
 *    about this override. That is the correct behaviour: those are system surfaces, not app ones.
 *  - **[AppLanguage.SYSTEM] returns the context unchanged**, so "follow the system" behaves exactly
 *    like an app that never had this feature.
 */
fun Context.withAppLanguage(language: AppLanguage): Context {
    val languageTag = language.languageTag ?: return this
    val locale = Locale.forLanguageTag(languageTag)

    val configuration = Configuration(resources.configuration).apply {
        setLocale(locale)
        // Setting the layout direction explicitly (rather than letting it be inferred) is what
        // makes an Arabic choice flip the whole UI even on a device configured for English.
        setLayoutDirection(locale)
    }
    return createConfigurationContext(configuration)
}

/** Maps Android's layout direction onto Compose's, for the composition local. */
fun Configuration.toComposeLayoutDirection(): LayoutDirection =
    if (layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL) {
        LayoutDirection.Rtl
    } else {
        LayoutDirection.Ltr
    }

/** The locale a language choice resolves to, or `null` for "follow the system". */
fun AppLanguage.toLocale(): Locale? = languageTag?.let(Locale::forLanguageTag)
