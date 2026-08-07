package com.krxkt.util

import com.krxkt.error.KrxError
import kotlin.test.Test
import kotlin.test.assertFailsWith

class DateUtilsTest {
    @Test
    fun `validateDate should accept valid date format`() {
        // Should not throw
        DateUtils.validateDate("20210122")
        DateUtils.validateDate("20210101")
        DateUtils.validateDate("20211231")
    }

    @Test
    fun `validateDate should reject invalid format`() {
        assertFailsWith<KrxError.InvalidDateError> {
            DateUtils.validateDate("2021-01-22")
        }
        assertFailsWith<KrxError.InvalidDateError> {
            DateUtils.validateDate("2021/01/22")
        }
        assertFailsWith<KrxError.InvalidDateError> {
            DateUtils.validateDate("21012")
        }
        assertFailsWith<KrxError.InvalidDateError> {
            DateUtils.validateDate("")
        }
    }

    @Test
    fun `validateDate should reject invalid month`() {
        assertFailsWith<KrxError.InvalidDateError> {
            DateUtils.validateDate("20211301")
        }
        assertFailsWith<KrxError.InvalidDateError> {
            DateUtils.validateDate("20210001")
        }
    }

    @Test
    fun `validateDate should reject invalid day`() {
        assertFailsWith<KrxError.InvalidDateError> {
            DateUtils.validateDate("20210100")
        }
        assertFailsWith<KrxError.InvalidDateError> {
            DateUtils.validateDate("20210132")
        }
    }

    @Test
    fun `validateDate should reject invalid calendar dates`() {
        // Feb 30 does not exist
        assertFailsWith<KrxError.InvalidDateError> {
            DateUtils.validateDate("20210230")
        }
        // Feb 29 in non-leap year
        assertFailsWith<KrxError.InvalidDateError> {
            DateUtils.validateDate("20210229")
        }
        // Apr 31 does not exist
        assertFailsWith<KrxError.InvalidDateError> {
            DateUtils.validateDate("20210431")
        }
        // Jun 31 does not exist
        assertFailsWith<KrxError.InvalidDateError> {
            DateUtils.validateDate("20210631")
        }
        // Sep 31 does not exist
        assertFailsWith<KrxError.InvalidDateError> {
            DateUtils.validateDate("20210931")
        }
        // Nov 31 does not exist
        assertFailsWith<KrxError.InvalidDateError> {
            DateUtils.validateDate("20211131")
        }
    }

    @Test
    fun `validateDate should accept leap year Feb 29`() {
        // 2020 is a leap year
        DateUtils.validateDate("20200229")
        // 2000 is a leap year (divisible by 400)
        DateUtils.validateDate("20000229")
    }

    @Test
    fun `validateDate should reject Feb 29 in century non-leap year`() {
        // 2100 is NOT a leap year (divisible by 100 but not 400)
        assertFailsWith<KrxError.InvalidDateError> {
            DateUtils.validateDate("21000229")
        }
    }

    // ====================================================
    // Year boundary tests
    // ====================================================

    @Test
    fun `validateDate should accept year boundaries`() {
        DateUtils.validateDate("19900101") // Min year
        DateUtils.validateDate("21001231") // Max year
    }

    @Test
    fun `validateDate should reject year below 1990`() {
        assertFailsWith<KrxError.InvalidDateError> {
            DateUtils.validateDate("19890101")
        }
    }

    @Test
    fun `validateDate should reject year above 2100`() {
        assertFailsWith<KrxError.InvalidDateError> {
            DateUtils.validateDate("21010101")
        }
    }

    // ====================================================
    // Month boundary tests
    // ====================================================

    @Test
    fun `validateDate should accept day 31 in 31-day months`() {
        DateUtils.validateDate("20210131") // Jan
        DateUtils.validateDate("20210331") // Mar
        DateUtils.validateDate("20210531") // May
        DateUtils.validateDate("20210731") // Jul
        DateUtils.validateDate("20210831") // Aug
        DateUtils.validateDate("20211031") // Oct
        DateUtils.validateDate("20211231") // Dec
    }

    @Test
    fun `validateDate should accept day 30 in 30-day months`() {
        DateUtils.validateDate("20210430") // Apr
        DateUtils.validateDate("20210630") // Jun
        DateUtils.validateDate("20210930") // Sep
        DateUtils.validateDate("20211130") // Nov
    }

    @Test
    fun `validateDate should reject day 31 in 30-day months`() {
        assertFailsWith<KrxError.InvalidDateError> {
            DateUtils.validateDate("20210431") // Apr
        }
        assertFailsWith<KrxError.InvalidDateError> {
            DateUtils.validateDate("20210631") // Jun
        }
        assertFailsWith<KrxError.InvalidDateError> {
            DateUtils.validateDate("20210931") // Sep
        }
        assertFailsWith<KrxError.InvalidDateError> {
            DateUtils.validateDate("20211131") // Nov
        }
    }

    @Test
    fun `validateDate should accept Feb 28 in non-leap year`() {
        DateUtils.validateDate("20210228")
    }

    @Test
    fun `validateDate should accept Feb 29 in leap years`() {
        DateUtils.validateDate("20200229") // Divisible by 4
        DateUtils.validateDate("20000229") // Divisible by 400
        DateUtils.validateDate("20240229") // Divisible by 4
    }

    @Test
    fun `validateDate should reject Feb 29 in non-leap years`() {
        assertFailsWith<KrxError.InvalidDateError> {
            DateUtils.validateDate("20210229")
        }
        assertFailsWith<KrxError.InvalidDateError> {
            DateUtils.validateDate("21000229") // Divisible by 100 but not 400
        }
        assertFailsWith<KrxError.InvalidDateError> {
            DateUtils.validateDate("19000229") // Historical edge: below 1990 anyway
        }
    }

    // ====================================================
    // Date range tests
    // ====================================================

    @Test
    fun `validateDateRange should accept valid range`() {
        // Should not throw
        DateUtils.validateDateRange("20210101", "20210131")
        DateUtils.validateDateRange("20210122", "20210122") // Same date
    }

    @Test
    fun `validateDateRange should reject start after end`() {
        assertFailsWith<KrxError.InvalidDateError> {
            DateUtils.validateDateRange("20210131", "20210101")
        }
    }
}
