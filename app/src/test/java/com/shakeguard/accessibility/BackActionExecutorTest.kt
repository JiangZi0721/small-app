package com.shakeguard.accessibility

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackActionExecutorTest {
    @Test
    fun waitsForExactSourceAndRetriesUntilForegroundReturnsToSource() = runTest {
        var currentPackage = "com.jingdong.app.mall"
        var attempts = 0
        val waits = mutableListOf<Long>()

        val returned = executeBackWithRetry(
            sourcePackage = "com.baidu.netdisk",
            targetPackage = "com.jingdong.app.mall",
            currentPackage = { currentPackage },
            performBack = {
                attempts++
                if (attempts == 3) currentPackage = "com.baidu.netdisk"
                true
            },
            launchSource = { false },
            wait = { waits += it },
        )

        assertTrue(returned)
        assertEquals(3, attempts)
        assertEquals(listOf(300L, 350L, 350L, 100L), waits)
    }

    @Test
    fun exactSourceForegroundChangeStopsRetry() = runTest {
        var currentPackage = "com.jingdong.app.mall"
        var attempts = 0

        val returned = executeBackWithRetry(
            sourcePackage = "com.baidu.netdisk",
            targetPackage = "com.jingdong.app.mall",
            currentPackage = { currentPackage },
            performBack = {
                attempts++
                currentPackage = "com.baidu.netdisk"
                true
            },
            launchSource = { error("source launch must not run") },
            wait = {},
        )

        assertTrue(returned)
        assertEquals(1, attempts)
    }

    @Test
    fun foregroundChangeDuringRetryWaitSkipsSecondBack() = runTest {
        var currentPackage = "com.jingdong.app.mall"
        var attempts = 0
        var waits = 0

        val returned = executeBackWithRetry(
            sourcePackage = "com.baidu.netdisk",
            targetPackage = "com.jingdong.app.mall",
            currentPackage = { currentPackage },
            performBack = {
                attempts++
                true
            },
            launchSource = { false },
            wait = {
                waits++
                if (waits == 2) currentPackage = "com.baidu.netdisk"
            },
        )

        assertTrue(returned)
        assertEquals(1, attempts)
    }

    @Test
    fun retryIsBoundedAndUnconfirmedActionIsFailure() = runTest {
        val returned = executeBackWithRetry(
            sourcePackage = "com.baidu.netdisk",
            targetPackage = "com.jingdong.app.mall",
            currentPackage = { "com.jingdong.app.mall" },
            performBack = { true },
            launchSource = { false },
            wait = {},
        )

        assertFalse(returned)
    }

    @Test
    fun transientIntermediatePackageThenTargetDoesNotCountAsSuccess() = runTest {
        var currentPackage = "com.sankuai.meituan"
        var attempts = 0
        var retryWaits = 0

        val returned = executeBackWithRetry(
            sourcePackage = "com.baidu.netdisk",
            targetPackage = "com.sankuai.meituan",
            currentPackage = { currentPackage },
            performBack = {
                attempts++
                currentPackage = "com.android.systemui"
                true
            },
            launchSource = { false },
            wait = {
                if (it == 350L) {
                    retryWaits++
                    if (retryWaits == 1) currentPackage = "com.sankuai.meituan"
                }
            },
        )

        assertFalse(returned)
        assertEquals(2, attempts)
    }

    @Test
    fun failedBackRelaunchesSourceOnceAndSucceedsOnlyAfterLeavingTarget() = runTest {
        var currentPackage = "com.sankuai.meituan"
        var backAttempts = 0
        var launchAttempts = 0

        val returned = executeBackWithRetry(
            sourcePackage = "com.baidu.netdisk",
            targetPackage = "com.sankuai.meituan",
            currentPackage = { currentPackage },
            performBack = {
                backAttempts++
                true
            },
            launchSource = {
                launchAttempts++
                currentPackage = "com.baidu.netdisk"
                true
            },
            wait = {},
        )

        assertTrue(returned)
        assertEquals(3, backAttempts)
        assertEquals(1, launchAttempts)
    }

    @Test
    fun sourceRelaunchFailureKeepsReturnFailed() = runTest {
        var launchAttempts = 0

        val returned = executeBackWithRetry(
            sourcePackage = "com.baidu.netdisk",
            targetPackage = "com.sankuai.meituan",
            currentPackage = { "com.sankuai.meituan" },
            performBack = { true },
            launchSource = {
                launchAttempts++
                false
            },
            wait = {},
        )

        assertFalse(returned)
        assertEquals(1, launchAttempts)
    }

    @Test
    fun sourceRelaunchToIntermediatePackageKeepsReturnFailed() = runTest {
        var currentPackage = "com.sankuai.meituan"

        val returned = executeBackWithRetry(
            sourcePackage = "com.baidu.netdisk",
            targetPackage = "com.sankuai.meituan",
            currentPackage = { currentPackage },
            performBack = { true },
            launchSource = {
                currentPackage = "com.android.systemui"
                true
            },
            wait = {},
        )

        assertFalse(returned)
    }

    @Test
    fun sourceAndTargetMatchNeverRelaunchesSource() = runTest {
        var launchAttempts = 0

        val returned = executeBackWithRetry(
            sourcePackage = "com.same.app",
            targetPackage = "com.same.app",
            currentPackage = { "com.same.app" },
            performBack = { true },
            launchSource = {
                launchAttempts++
                true
            },
            wait = {},
        )

        assertFalse(returned)
        assertEquals(0, launchAttempts)
    }
}
