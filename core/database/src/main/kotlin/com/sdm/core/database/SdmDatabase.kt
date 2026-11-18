package com.sdm.core.database

import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.RoomDatabase

@Entity(tableName = "queues")
data class QueueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val maxConcurrent: Int,
)

@Database(entities = [QueueEntity::class], version = 1, exportSchema = true)
abstract class SdmDatabase : RoomDatabase()
