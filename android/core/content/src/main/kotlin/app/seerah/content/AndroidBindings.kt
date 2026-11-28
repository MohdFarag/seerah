package app.seerah.content

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.StatFs
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import app.seerah.content.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Production [AvailableSpaceProvider] backed by Android [StatFs]. */
internal class StatFsAvailableSpaceProvider @Inject constructor() : AvailableSpaceProvider {
    override fun availableBytes(directory: File): Long = StatFs(directory.path).availableBytes
}

/**
 * Production [AppVersionCodeProvider] querying `PackageManager` off the main thread using API 33
 * `PackageInfoFlags` with a safe pre-33 fallback and [PackageInfoCompat.getLongVersionCode].
 */
internal class AndroidAppVersionCodeProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : AppVersionCodeProvider {
    override suspend fun currentVersionCode(): Long = withContext(ioDispatcher) {
        try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0L),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            PackageInfoCompat.getLongVersionCode(info)
        } catch (exc: PackageManager.NameNotFoundException) {
            throw IOException("Package info missing for ${context.packageName}", exc)
        }
    }
}

/** Production [PackageStorageLogger] backed by `android.util.Log`. */
internal class AndroidPackageStorageLogger @Inject constructor() : PackageStorageLogger {
    override fun warn(tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) {
            Log.w(tag, message, throwable)
        } else {
            Log.w(tag, message)
        }
    }
}

/** Internal Hilt-injectable source of [KeyRingState] avoiding `Function0` JVM wildcard ambiguity. */
internal fun interface TrustedKeyRingSource {
    fun currentKeyRing(): KeyRingState
}

/** Lazily reads `res/raw/trusted_package_keys.json` on first verification attempt (zero DI init I/O). */
internal class RawResourceKeyRingProvider @Inject constructor(@ApplicationContext private val context: Context) :
    TrustedKeyRingSource {
    private val lock = Any()

    @Volatile
    private var cached: KeyRingState? = null

    override fun currentKeyRing(): KeyRingState {
        cached?.let { return it }
        return synchronized(lock) {
            cached ?: readFromRawResource().also { cached = it }
        }
    }

    private fun readFromRawResource(): KeyRingState = try {
        val rawBytes = context.resources.openRawResource(R.raw.trusted_package_keys).use { it.readBytes() }
        TrustedKeyRingLoader.loadFromBytes(rawBytes)
    } catch (exc: IOException) {
        KeyRingState.Invalid("Cannot read trusted_package_keys.json resource: ${exc.message}")
    } catch (exc: RuntimeException) {
        KeyRingState.Invalid("Cannot open trusted_package_keys.json resource: ${exc.message}")
    }
}
