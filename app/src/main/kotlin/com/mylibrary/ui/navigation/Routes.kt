package com.mylibrary.ui.navigation

/**
 * Every route in the app, in one place.
 *
 * String routes rather than Kotlin-serialization type-safe routes: the app has five destinations
 * and two arguments, and typed routes would mean adding the serialization plugin and a
 * `@Serializable` object per screen to gain compile-time checking on two `Long`s. The route
 * constants are private to this file so nothing can invent a route string elsewhere.
 */
object Routes {
    const val LIBRARY = "library"
    const val SEARCH = "search"
    const val SETTINGS = "settings"

    /**
     * The colour setup.
     *
     * A route as well as a first-run screen, so that reopening it from Settings is an ordinary
     * navigation rather than a second copy of the screen behind a flag. Deliberately not in
     * [topLevel]: it is somewhere you go and come back from, not a place to sit.
     */
    const val COLOR_SETUP = "color-setup"

    const val BOOK_ID_ARG = "bookId"

    const val BOOK_DETAILS = "book/{$BOOK_ID_ARG}"
    const val READER = "reader/{$BOOK_ID_ARG}"

    /** The destinations shown in the navigation bar and rail, in order. */
    val topLevel: List<String> = listOf(LIBRARY, SEARCH, SETTINGS)

    fun bookDetails(bookId: Long): String = "book/$bookId"

    fun reader(bookId: Long): String = "reader/$bookId"

    fun isTopLevel(route: String?): Boolean = route != null && route in topLevel
}
