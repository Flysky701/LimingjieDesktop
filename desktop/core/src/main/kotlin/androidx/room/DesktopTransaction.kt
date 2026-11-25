package androidx.room
import androidx.room.immediateTransaction
suspend fun <R> RoomDatabase.withTransaction(block: suspend () -> R): R = useWriterConnection { connection -> connection.immediateTransaction { block() } }
