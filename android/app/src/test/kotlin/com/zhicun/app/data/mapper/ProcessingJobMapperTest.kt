package com.zhicun.app.data.mapper

import com.zhicun.app.data.local.entity.ProcessingJobEntity
import com.zhicun.app.domain.model.JobStatus
import com.zhicun.app.domain.model.JobType
import com.zhicun.app.domain.model.ProcessingJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProcessingJobMapperTest {

    private fun entity(
        nextRetryAt: Long? = null,
        errorCategory: String? = null,
        startedAt: Long? = null,
        finishedAt: Long? = null,
    ) = ProcessingJobEntity(
        id = "job-1",
        recordId = "rec-1",
        jobType = JobType.OCR,
        status = JobStatus.QUEUED,
        attempts = 0,
        maxAttempts = ProcessingJob.DEFAULT_MAX_ATTEMPTS,
        nextRetryAt = nextRetryAt,
        errorCategory = errorCategory,
        startedAt = startedAt,
        finishedAt = finishedAt,
    )

    @Test
    fun ProcessingJobEntity_queuedJob_mapsWithNullTimestamps() {
        val domain = entity().toDomain()

        assertEquals("job-1", domain.id)
        assertEquals("rec-1", domain.recordId)
        assertEquals(JobType.OCR, domain.jobType)
        assertEquals(JobStatus.QUEUED, domain.status)
        assertEquals(0, domain.attempts)
        assertEquals(3, domain.maxAttempts)
        assertNull(domain.nextRetryAt)
        assertNull(domain.errorCategory)
        assertNull(domain.startedAt)
        assertNull(domain.finishedAt)
    }

    @Test
    fun ProcessingJobEntity_failedJob_mapsRetryFields() {
        val domain = entity(
            nextRetryAt = 9_000L,
            errorCategory = "NETWORK",
            startedAt = 7_000L,
            finishedAt = 8_000L,
        ).copy(status = JobStatus.FAILED, attempts = 2).toDomain()

        assertEquals(JobStatus.FAILED, domain.status)
        assertEquals(2, domain.attempts)
        assertEquals(9_000L, domain.nextRetryAt)
        assertEquals("NETWORK", domain.errorCategory)
        assertEquals(7_000L, domain.startedAt)
        assertEquals(8_000L, domain.finishedAt)
    }

    @Test
    fun ProcessingJob_roundTripThroughEntity_preservesAllFields() {
        val domain = entity(nextRetryAt = 9_000L, errorCategory = "TIMEOUT").toDomain()

        assertEquals(domain, domain.toEntity().toDomain())
    }
}
