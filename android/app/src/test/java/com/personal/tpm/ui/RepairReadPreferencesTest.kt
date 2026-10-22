package com.personal.tpm.ui

import org.junit.Assert.*
import org.junit.Test

class RepairReadPreferencesTest {
    @Test fun disablingExperimentRestoresDefaultsWithoutLosingChosenLevels() {
        try {
            AppPreferences.updateRepairPageSize(200)
            AppPreferences.updateRepairConcurrency(5)
            AppPreferences.updateRepairReadExperiment(true)
            assertEquals(200,AppPreferences.effectiveRepairPageSize)
            assertEquals(5,AppPreferences.effectiveRepairConcurrency)
            AppPreferences.updateRepairReadExperiment(false)
            assertEquals(500,AppPreferences.effectiveRepairPageSize)
            assertEquals(8,AppPreferences.effectiveRepairConcurrency)
            assertEquals(200,AppPreferences.repairPageSize)
            assertThrows(IllegalArgumentException::class.java){AppPreferences.updateRepairPageSize(999)}
        } finally {
            AppPreferences.updateRepairReadExperiment(false)
            AppPreferences.updateRepairPageSize(500)
            AppPreferences.updateRepairConcurrency(8)
        }
    }
}
