package io.github.mangi.eta.fork.learning

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        LearningCandidateEntity::class,
        LearningEvidenceEntity::class,
        LearningRuleEntity::class,
        LearningRuleVersionEntity::class,
        LearningFeedbackEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
internal abstract class LearningDatabase : RoomDatabase() {
    abstract fun learningDao(): LearningDao

    companion object {
        @Volatile
        private var instance: LearningDatabase? = null

        fun get(context: Context): LearningDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LearningDatabase::class.java,
                    "eta_learning.db",
                ).build().also { instance = it }
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
