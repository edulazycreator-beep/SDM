package com.sdm.core.data

import android.content.Context
import androidx.room.Room
import com.sdm.core.database.SdmDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): SdmDatabase =
        Room.databaseBuilder(context, SdmDatabase::class.java, "sdm.db").build()
}
