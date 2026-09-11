package com.clipsync.images

import android.content.ContentResolver
import android.content.pm.ProviderInfo
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

@SdkSuppress(minSdkVersion = 29)
class SharedImageReaderTest {
    private lateinit var resolver: ContentResolver
    @Before fun setup() {
        StalledImageProvider.reset()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = StalledImageProvider()
        provider.attachInfo(context, ProviderInfo().apply {
            authority = "com.clipsync.app.test.stalled-images"
            applicationInfo = context.applicationInfo
            exported = false
        })
        resolver = ContentResolver.wrap(provider)
    }
    private fun uri(path: String) = Uri.parse("content://com.clipsync.app.test.stalled-images/$path")
    @After fun cleanup() { StalledImageProvider.reset() }

    @Test fun cancellationInterruptsProviderOpenAndReaderRemainsUsable() = runBlocking {
        StalledImageProvider.reset()
        val job = launch(Dispatchers.Default) { SharedImageReader.read(resolver, uri("open-stall")) }
        assertTrue(withContext(Dispatchers.IO) { StalledImageProvider.entered.await(2, TimeUnit.SECONDS) })
        withTimeout(1000) { job.cancelAndJoin() }
        assertTrue("Provider must receive its cancellation signal",
            withContext(Dispatchers.IO) { StalledImageProvider.openCancelled.await(1, TimeUnit.SECONDS) })
        assertValidImageEventually()
    }

    @Test fun deadlineClosesStalledReadAndReaderRemainsUsable() = runBlocking {
        // Two operations prove that repeated timeouts do not consume both bounded worker slots.
        repeat(2) {
            val error = runCatching { withTimeout(2000) { SharedImageReader.read(resolver, uri("read-stall"), timeoutMs = 200) } }.exceptionOrNull()
            assertTrue("Expected reader deadline, got $error", error is IOException)
            assertEquals("Image read timed out", error?.message)
        }
        assertValidImageEventually()
    }

    @Test fun activityStyleCancellationClosesStalledRead() = runBlocking {
        repeat(2) {
            StalledImageProvider.entered = java.util.concurrent.CountDownLatch(1)
            val job = launch(Dispatchers.Default) { SharedImageReader.read(resolver, uri("read-stall")) }
            assertTrue(withContext(Dispatchers.IO) { StalledImageProvider.entered.await(2, TimeUnit.SECONDS) })
            withTimeout(1000) { job.cancelAndJoin() }
        }
        assertValidImageEventually()
    }

    private suspend fun assertValidImageEventually() {
        withTimeout(3000) {
            while (true) {
                val result = runCatching { SharedImageReader.read(resolver, uri("valid")) }
                if (result.isSuccess) {
                    assertEquals("image/png", result.getOrThrow().mime)
                    return@withTimeout
                }
                delay(25)
            }
        }
    }
}
