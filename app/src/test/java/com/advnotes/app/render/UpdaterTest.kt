package com.advnotes.app.render

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.advnotes.app.update.Update
import com.advnotes.app.update.Updater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdaterTest {
    private val ctx = ApplicationProvider.getApplicationContext<Application>()
    private fun update(size: Long) = Update("9.9.9", "https://example.invalid/a.apk", size, "")
    private fun apkFile() = File(File(ctx.cacheDir, "updates").apply { mkdirs() }, "adv-notes-9.9.9.apk")

    @Test fun comparesVersionsNumerically() {
        assertTrue(Updater.isNewer("v1.2.8", "1.2.7"))
        assertTrue(Updater.isNewer("1.10.0", "1.9.9"))
        assertTrue(Updater.isNewer("v2.0", "1.99.99"))
        assertFalse(Updater.isNewer("v1.2.7", "1.2.7"))
        assertFalse(Updater.isNewer("1.2.6", "1.2.7"))
    }

    @Test fun nothingDownloadedMeansNothingToReuse() {
        apkFile().delete()
        assertNull(Updater.cachedApk(ctx, update(100)))
    }

    @Test fun aPartialOrForeignFileIsNeverReused() {
        apkFile().writeText("not an apk at all")
        assertNull(Updater.cachedApk(ctx, update(10_000)))        // wrong size
        assertNull(Updater.cachedApk(ctx, update(17)))            // right size, but not a real APK of that version
        apkFile().delete()
    }
}
