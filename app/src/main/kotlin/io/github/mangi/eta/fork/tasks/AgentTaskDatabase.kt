package io.github.mangi.eta.fork.tasks

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        AgentTaskDefinitionEntity::class,
        AgentTaskScheduleEntity::class,
        AgentTaskRunEntity::class,
        AgentTaskLeaseEntity::class,
        AgentTaskEventEntity::class,
        AgentTaskAttentionEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
internal abstract class AgentTaskDatabase : RoomDatabase() {
    abstract fun agentTaskDao(): AgentTaskDao

    companion object {
        @Volatile
        private var instance: AgentTaskDatabase? = null

        fun get(context: Context): AgentTaskDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AgentTaskDatabase::class.java,
                    "eta_task.db",
                )
                    .build()
                    .also { instance = it }
            }

        @VisibleForTesting
        internal fun closeForTests() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }
    }
}
