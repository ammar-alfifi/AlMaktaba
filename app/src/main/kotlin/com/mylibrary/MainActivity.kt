package com.mylibrary

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint

/**
 * The single Activity in MyLibrary.
 *
 * Navigation, theming and language all live in the Compose tree, so this class does exactly three
 * things: opt into edge-to-edge drawing, hand control to [com.mylibrary.ui.MyLibraryApp], and — via
 * `enableOnBackInvokedCallback` in the manifest — opt into predictive back so that a back gesture
 * peeks at the previous screen instead of jumping.
 *
 * A single Activity also means the reader's state survives configuration changes for free, which
 * matters: rotating a tablet mid-page must not reload a 400 MB PDF.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must be called before super.onCreate so the window is set up edge-to-edge before the
        // first frame; calling it after causes a visible system-bar jump on launch.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            com.mylibrary.ui.MyLibraryApp()
        }
    }
}
