package com.mylibrary

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import com.mylibrary.feature.library.toImportCandidate
import dagger.hilt.android.AndroidEntryPoint

/**
 * The single Activity in MyLibrary.
 *
 * Navigation, theming and language all live in the Compose tree, so this class does exactly four
 * things: opt into edge-to-edge drawing, hand control to [com.mylibrary.ui.MyLibraryApp], — via
 * `enableOnBackInvokedCallback` in the manifest — opt into predictive back so that a back gesture
 * peeks at the previous screen instead of jumping, and receive the books other apps send here.
 *
 * A single Activity also means the reader's state survives configuration changes for free, which
 * matters: rotating a tablet mid-page must not reload a 400 MB PDF.
 *
 * **Receiving books.** The manifest advertises VIEW and SEND filters for every supported format;
 * this class is what makes those filters true. A URI that arrives is turned into an
 * [ImportCandidate] and handed to [AppViewModel], which imports it and opens the reader — the same
 * road a file picked from the shelf takes, so a book opened from a file manager ends up on the
 * shelf and open in front of the reader, not silently ignored. `singleTop` keeps re-opening the
 * same book from recycling the Activity, and [onNewIntent] catches those.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must be called before super.onCreate so the window is set up edge-to-edge before the
        // first frame; calling it after causes a visible system-bar jump on launch.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            com.mylibrary.ui.MyLibraryApp()
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /**
     * Extracts the document URI from a VIEW or SEND intent, if this is one.
     *
     * VIEW carries the file as `data`; SEND as the `EXTRA_STREAM` parcelable. Anything else — the
     * launcher intent, a malformed share — falls through and is ignored, which is the honest
     * response to an intent this app did not advertise.
     */
    private fun handleIntent(intent: Intent?) {
        // A launcher shortcut: the app is being asked to *go somewhere* rather than to open a file,
        // so it is answered before the document road and carries no URI.
        if (intent?.action == ACTION_OPEN_SEARCH) {
            viewModel.onIntent(AppIntent.OpenSearchRequested)
            return
        }

        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            // The typed variant via compat, because the platform's `getParcelableExtra(String)`
            // pair is deprecated from API 33 and this class targets newer devices.
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(
                intent,
                Intent.EXTRA_STREAM,
                Uri::class.java,
            )
            else -> null
        } ?: return

        // Resolves name, size and MIME from the provider and takes the read grant. A URI the
        // provider cannot even name yields null, and there is genuinely nothing to open.
        val candidate = toImportCandidate(uri) ?: return
        viewModel.onIntent(AppIntent.OpenExternalFile(candidate))
    }

    companion object {
        /**
         * The action the "Search" launcher shortcut carries.
         *
         * A custom action rather than an extra, so the shortcut resolves through this Activity's
         * own intent filter and needs no hardcoded package name — which would be wrong in the debug
         * build, whose application id carries a `.debug` suffix.
         */
        const val ACTION_OPEN_SEARCH = "com.mylibrary.action.OPEN_SEARCH"
    }
}
