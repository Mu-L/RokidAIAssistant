package com.example.rokidphone.data.db

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class RecordingRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val dao = mockk<RecordingDao>(relaxed = true)
    private lateinit var repository: RecordingRepository

    @Before
    fun setUp() {
        val context = mockk<Context>()
        every { context.filesDir } returns temporary.root
        val database = mockk<AppDatabase>()
        every { database.recordingDao() } returns dao
        mockkObject(AppDatabase.Companion)
        every { AppDatabase.getInstance(context) } returns database
        repository = RecordingRepository::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }.newInstance(context)
    }

    @After
    fun tearDown() {
        repository.release()
        unmockkAll()
    }

    @Test
    fun `glasses audio keeps requested identity metadata duration and wav payload`() = runTest {
        val pcm = ByteArray(32_000) { (it % 127).toByte() }
        val recording = repository.saveGlassesRecording(
            pcm, transcript = "transcript", aiResponse = "answer", providerId = "provider",
            modelId = "model", recordingId = "requested-id"
        )!!
        assertThat(recording.id).isEqualTo("requested-id")
        assertThat(recording.durationMs).isEqualTo(1000L)
        assertThat(recording.transcript).isEqualTo("transcript")
        assertThat(recording.aiResponse).isEqualTo("answer")
        assertThat(recording.providerId).isEqualTo("provider")
        assertThat(recording.modelId).isEqualTo("model")
        val wav = File(recording.filePath).readBytes()
        assertThat(String(wav.copyOfRange(0, 4), Charsets.US_ASCII)).isEqualTo("RIFF")
        assertThat(wav.copyOfRange(44, wav.size)).isEqualTo(pcm)
        coVerify(exactly = 1) { dao.insert(recording) }
    }

    @Test
    fun `cancelled database insert removes incomplete audio and propagates cancellation`() = runTest {
        coEvery { dao.insert(any()) } throws CancellationException("cancelled save")
        val failure = runCatching { repository.saveGlassesRecording(byteArrayOf(1, 2)) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(CancellationException::class.java)
        assertThat(File(temporary.root, "recordings").listFiles()).isEmpty()
    }

    @Test
    fun `failed database insert removes incomplete audio and reports no saved recording`() = runTest {
        coEvery { dao.insert(any()) } throws IOException("storage unavailable")
        assertThat(repository.saveGlassesRecording(byteArrayOf(1, 2))).isNull()
        assertThat(File(temporary.root, "recordings").listFiles()).isEmpty()
    }

    @Test
    fun `failed cleanup is logged without swallowing cancellation`() = runTest {
        coEvery { dao.insert(any()) } answers {
            val file = File(firstArg<RecordingEntity>().filePath)
            check(file.delete())
            check(file.mkdir())
            File(file, "occupied").writeText("another writer")
            throw CancellationException("cancelled save")
        }
        val failure = runCatching { repository.saveGlassesRecording(byteArrayOf(1, 2)) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(CancellationException::class.java)

        assertThat(ShadowLog.getLogsForTag("RecordingRepository")
            .any { it.msg.startsWith("Failed to delete incomplete recording:") }).isTrue()
    }

    @Test
    fun `search escapes SQL wildcards before reaching the DAO`() = runTest {
        every { dao.searchRecordings(any()) } returns flowOf(emptyList())
        assertThat(repository.searchRecordings("""100%_done\ready""").first()).isEmpty()
        verify { dao.searchRecordings("""100\%\_done\\ready""") }
    }
}
