package com.sdm.core.data

import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sdm.core.common.FileCategory
import com.sdm.core.data.security.SecretStore
import com.sdm.core.data.storage.OutputStore
import com.sdm.core.database.DownloadEntity
import com.sdm.core.database.SdmDatabase
import com.sdm.domain.DestinationType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StorageAndSecretsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Segments write out of order at positions; the output must equal the original bytes. */
    @Test fun positionalWritesToMediaStoreAndPrivateStorage() = runTest {
        val store = OutputStore(context)
        val types = buildList { add(DestinationType.APP_PRIVATE); if (Build.VERSION.SDK_INT >= 29) add(DestinationType.MEDIASTORE) }
        for (type in types) {
            val data = ByteArray(300_000) { (it * 31 % 251).toByte() }
            val handle = store.create("sdm-test-${type.name}.bin", "application/octet-stream", FileCategory.OTHER, type, null)
            store.open(handle.uri).use { sink ->
                sink.setLength(data.size.toLong())
                sink.write(200_000, data, 200_000, 100_000)
                sink.write(0, data, 0, 100_000)
                sink.write(100_000, data, 100_000, 100_000)
                sink.sync()
                assertEquals(data.size.toLong(), sink.size())
                val back = ByteArray(data.size)
                var read = 0
                while (read < back.size) read += sink.read(read.toLong(), back, read, back.size - read)
                assertArrayEquals(data, back)
            }
            val final = store.finalize(handle.uri, type, null, handle.displayName, null)
            assertTrue(store.exists(final))
            assertTrue(store.delete(final))
        }
    }

    @Test fun secretsAreEncryptedAndRoundTrip() = runTest {
        val db = Room.inMemoryDatabaseBuilder(context, SdmDatabase::class.java).build()
        val id = db.downloads().insert(DownloadEntity(originalUrl = "https://h/a", fileName = "a", createdAt = 1, updatedAt = 1))
        val secrets = SecretStore(db.secrets())
        secrets.put(id, mapOf("Cookie" to "session=top-secret"))
        val raw = db.secrets().get(id)!!
        assertTrue("ciphertext must not contain the cookie", !String(raw.ciphertext, Charsets.ISO_8859_1).contains("top-secret"))
        assertEquals("session=top-secret", secrets.get(id)["Cookie"])
        db.close()
    }
}
