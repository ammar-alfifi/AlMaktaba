package com.mylibrary.core.common

/**
 * The result of an operation that can fail in a way the caller is expected to handle.
 *
 * MyLibrary deliberately avoids throwing across layer boundaries for *expected* failures — a
 * corrupt PDF, a deleted book, an archive that needs a password — because those are ordinary
 * outcomes of reading files a user picked, not bugs. Genuine programming errors still throw.
 *
 * [AppError] is a sealed hierarchy rather than raw exceptions so that the presentation layer can
 * map each case to an Arabic or English message without inspecting exception types.
 */
sealed interface AppResult<out T> {
    data class Success<T>(val data: T) : AppResult<T>
    data class Failure(val error: AppError) : AppResult<Nothing>
}

/** Maps the success value while leaving a failure untouched. */
inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Success -> AppResult.Success(transform(data))
    is AppResult.Failure -> this
}

/** Chains an operation that can itself fail. */
inline fun <T, R> AppResult<T>.flatMap(transform: (T) -> AppResult<R>): AppResult<R> = when (this) {
    is AppResult.Success -> transform(data)
    is AppResult.Failure -> this
}

/** Runs [block] and captures any thrown exception as [AppError.Unexpected]. */
inline fun <T> runCatchingApp(block: () -> T): AppResult<T> = try {
    AppResult.Success(block())
} catch (cancellation: kotlin.coroutines.cancellation.CancellationException) {
    // Never swallow cancellation: it is control flow, not a failure.
    throw cancellation
} catch (error: Throwable) {
    AppResult.Failure(AppError.Unexpected(error))
}

/** The success value, or `null` when this is a failure. */
fun <T> AppResult<T>.getOrNull(): T? = (this as? AppResult.Success)?.data

/** The error, or `null` when this is a success. */
fun <T> AppResult<T>.errorOrNull(): AppError? = (this as? AppResult.Failure)?.error
