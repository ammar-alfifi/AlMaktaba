package com.mylibrary

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point and the root of Hilt's dependency graph.
 *
 * Everything MyLibrary needs is created lazily by Hilt from here down, so this class stays
 * deliberately empty: no service locators, no static singletons.
 */
@HiltAndroidApp
class MyLibraryApplication : Application()
