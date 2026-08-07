package com.krxkt.util

import com.krxkt.error.KrxError

/**
 * 날짜 유틸리티
 */
object DateUtils {
    private val DATE_PATTERN = Regex("^\\d{8}$")

    /**
     * KRX 날짜 형식 검증 (yyyyMMdd)
     *
     * @param date 날짜 문자열
     * @throws KrxError.InvalidDateError 형식이 잘못된 경우
     */
    fun validateDate(date: String) {
        if (!DATE_PATTERN.matches(date)) {
            throw KrxError.InvalidDateError(date)
        }

        val year = date.substring(0, 4).toIntOrNull() ?: throw KrxError.InvalidDateError(date)
        val month = date.substring(4, 6).toIntOrNull() ?: throw KrxError.InvalidDateError(date)
        val day = date.substring(6, 8).toIntOrNull() ?: throw KrxError.InvalidDateError(date)

        if (year < 1990 || year > 2100) throw KrxError.InvalidDateError(date)
        if (month < 1 || month > 12) throw KrxError.InvalidDateError(date)

        // Calendar-correct day validation per month
        val maxDay =
            when (month) {
                1, 3, 5, 7, 8, 10, 12 -> 31
                4, 6, 9, 11 -> 30
                2 -> if (isLeapYear(year)) 29 else 28
                else -> 31
            }
        if (day < 1 || day > maxDay) throw KrxError.InvalidDateError(date)
    }

    private fun isLeapYear(year: Int): Boolean {
        return (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)
    }

    /**
     * 날짜 범위 검증 (시작일 <= 종료일)
     */
    fun validateDateRange(
        startDate: String,
        endDate: String,
    ) {
        validateDate(startDate)
        validateDate(endDate)

        if (startDate > endDate) {
            throw KrxError.InvalidDateError("Start date ($startDate) must be before or equal to end date ($endDate)")
        }
    }
}
