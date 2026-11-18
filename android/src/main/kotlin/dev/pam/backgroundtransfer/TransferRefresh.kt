package dev.pam.backgroundtransfer

/**
 * Stale step outputs. A step whose URL or headers come from an earlier step
 * (`{{steps.sign.data.upload_url}}`, `headersFrom`) may run hours after that
 * step (offline, retry backoff, process death, manual retry): a signed URL
 * has expired by then and the storage answers 400/401/403 forever (S3 and GCS
 * answer an expired signature with 403 or 400). On 400/401/403 the
 * transfer rewinds once per run to the producing step, which runs again for
 * a fresh value; a second failure with the fresh value fails the transfer.
 */
internal object TransferRefresh {
    private val REFRESH_STATUSES = setOf(400, 401, 403)
    private val REFERENCE = Regex("""\{\{\s*(response|steps\.([A-Za-z0-9_\-]+))[.\s}]""")

    /** Index of the step to resume from, or null when the failure stands. */
    fun rewindTo(steps: List<StepSpec>, failed: Int, statusCode: Int, refreshed: Set<Int>): Int? {
        if (statusCode !in REFRESH_STATUSES) return null
        val step = steps.getOrNull(failed) ?: return null
        val producers = producers(steps, failed, step)
        return producers.filter { it !in refreshed && replayable(steps[it]) }.minOrNull()
    }

    /** Steps whose responses build the request target (URL, headers) of [step]. */
    private fun producers(steps: List<StepSpec>, index: Int, step: StepSpec): Set<Int> {
        val texts = listOf(step.url) + step.headers.mapNotNull { it.value } +
            listOfNotNull(step.headersFrom?.let { "{{$it}}" })
        val result = mutableSetOf<Int>()
        texts.forEach { text ->
            REFERENCE.findAll(text).forEach { match ->
                val name = match.groupValues[2]
                val producer = if (name.isEmpty()) index - 1 else (index - 1 downTo 0).firstOrNull { steps[it].name == name }
                if (producer != null && producer >= 0) result += producer
            }
        }
        return result
    }

    /** A step replays from the checkpoint only when it does not read `{{response}}` (the previous step's answer is not kept). */
    private fun replayable(step: StepSpec): Boolean {
        val texts = buildList {
            add(step.url)
            step.headers.mapNotNullTo(this) { it.value }
            step.headersFrom?.let { add("{{$it}}") }
            when (val body = step.body) {
                is BodySpec.Json -> add(body.value.toString())
                is BodySpec.Form -> add(body.fields.toString())
                is BodySpec.Multipart -> body.parts.filterIsInstance<PartSpec.Field>().filterNot { it.literal }.mapTo(this) { it.value }
                else -> Unit
            }
        }
        return texts.none { text -> REFERENCE.findAll(text).any { it.groupValues[2].isEmpty() } }
    }
}
