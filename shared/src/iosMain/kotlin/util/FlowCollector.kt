package util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class Cancellable(private val job: Job) {
    fun cancel() {
        job.cancel()
    }
}

fun <T> Flow<T>.collect(
    onEach: (T) -> Unit,
    onCompletion: (Throwable?) -> Unit,
): Cancellable {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val job = scope.launch {
        try {
            collect { value ->
                onEach(value)
            }
            onCompletion(null)
        } catch (e: Throwable) {
            onCompletion(e)
        }
    }
    return Cancellable(job)
}
