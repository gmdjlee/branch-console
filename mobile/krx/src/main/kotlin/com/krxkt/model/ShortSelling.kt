package com.krxkt.model

import com.google.gson.JsonObject
import com.krxkt.parser.KrxJsonParser

/**
 * 공매도 거래 데이터 (전종목 특정일)
 *
 * KRX API 응답 필드 매핑 (MDCSTAT30101):
 * - ISU_SRT_CD → ticker
 * - ISU_ABBRV → name
 * - CVSRTSELL_TRDVOL → shortVolume (공매도 거래량)
 * - CVSRTSELL_TRDVAL → shortValue (공매도 거래대금)
 * - ACC_TRDVOL → totalVolume (전체 거래량)
 * - ACC_TRDVAL → totalValue (전체 거래대금)
 * - TRDVOL_WT → volumeRatio (거래량 비중, %)
 *
 * @property ticker 종목코드 (예: "005930")
 * @property name 종목명 (예: "삼성전자")
 * @property shortVolume 공매도 거래량 (주)
 * @property shortValue 공매도 거래대금 (원)
 * @property totalVolume 전체 거래량 (주)
 * @property totalValue 전체 거래대금 (원)
 * @property volumeRatio 공매도 비중 (%)
 */
data class ShortSelling(
    val ticker: String,
    val name: String,
    val shortVolume: Long,
    val shortValue: Long,
    val totalVolume: Long,
    val totalValue: Long,
    val volumeRatio: Double?,
) {
    companion object {
        /**
         * KRX JSON 응답에서 ShortSelling 객체 생성
         *
         * @param json OutBlock_1 배열의 개별 JSON 객체
         * @return ShortSelling 또는 null (파싱 실패 시)
         */
        fun fromJson(json: JsonObject): ShortSelling? {
            return try {
                val ticker = json.get("ISU_SRT_CD")?.asString ?: return null

                ShortSelling(
                    ticker = ticker,
                    name = json.get("ISU_ABBRV")?.asString ?: "",
                    shortVolume = KrxJsonParser.parseLong(json.get("CVSRTSELL_TRDVOL")?.asString) ?: 0L,
                    shortValue = KrxJsonParser.parseLong(json.get("CVSRTSELL_TRDVAL")?.asString) ?: 0L,
                    totalVolume = KrxJsonParser.parseLong(json.get("ACC_TRDVOL")?.asString) ?: 0L,
                    totalValue = KrxJsonParser.parseLong(json.get("ACC_TRDVAL")?.asString) ?: 0L,
                    volumeRatio = KrxJsonParser.parseDouble(json.get("TRDVOL_WT")?.asString),
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * 공매도 비중 계산 (거래량 기준)
     */
    val calculatedVolumeRatio: Double
        get() = if (totalVolume > 0) (shortVolume.toDouble() / totalVolume * 100) else 0.0

    /**
     * 공매도 비중 계산 (거래대금 기준)
     */
    val calculatedValueRatio: Double
        get() = if (totalValue > 0) (shortValue.toDouble() / totalValue * 100) else 0.0
}

/**
 * 공매도 거래 일별 추이 데이터 (개별종목)
 *
 * KRX API 응답 필드 매핑 (MDCSTAT30102):
 * - TRD_DD → date (거래일)
 * - CVSRTSELL_TRDVOL → shortVolume (공매도 거래량)
 * - CVSRTSELL_TRDVAL → shortValue (공매도 거래대금)
 * - ACC_TRDVOL → totalVolume (전체 거래량)
 * - ACC_TRDVAL → totalValue (전체 거래대금)
 *
 * @property date 거래일 (yyyyMMdd)
 * @property shortVolume 공매도 거래량 (주)
 * @property shortValue 공매도 거래대금 (원)
 * @property totalVolume 전체 거래량 (주)
 * @property totalValue 전체 거래대금 (원)
 */
data class ShortSellingHistory(
    val date: String,
    val shortVolume: Long,
    val shortValue: Long,
    val totalVolume: Long,
    val totalValue: Long,
) {
    companion object {
        /**
         * KRX JSON 응답에서 ShortSellingHistory 객체 생성
         *
         * @param json OutBlock_1 배열의 개별 JSON 객체
         * @return ShortSellingHistory 또는 null (파싱 실패 시)
         */
        fun fromJson(json: JsonObject): ShortSellingHistory? {
            return try {
                val dateRaw = json.get("TRD_DD")?.asString ?: return null
                val date = dateRaw.replace("/", "")

                ShortSellingHistory(
                    date = date,
                    shortVolume = KrxJsonParser.parseLong(json.get("CVSRTSELL_TRDVOL")?.asString) ?: 0L,
                    shortValue = KrxJsonParser.parseLong(json.get("CVSRTSELL_TRDVAL")?.asString) ?: 0L,
                    totalVolume = KrxJsonParser.parseLong(json.get("ACC_TRDVOL")?.asString) ?: 0L,
                    totalValue = KrxJsonParser.parseLong(json.get("ACC_TRDVAL")?.asString) ?: 0L,
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * 공매도 비중 (거래량 기준, %)
     */
    val volumeRatio: Double
        get() = if (totalVolume > 0) (shortVolume.toDouble() / totalVolume * 100) else 0.0

    /**
     * 공매도 비중 (거래대금 기준, %)
     */
    val valueRatio: Double
        get() = if (totalValue > 0) (shortValue.toDouble() / totalValue * 100) else 0.0
}

/**
 * 공매도 잔고 데이터 (전종목)
 *
 * KRX API 응답 필드 매핑 (MDCSTAT30501):
 * - ISU_SRT_CD → ticker
 * - ISU_ABBRV → name
 * - BAL_QTY → balanceQuantity (잔고수량)
 * - BAL_AMT → balanceAmount (잔고금액)
 * - LIST_SHRS → listedShares (상장주식수)
 * - BAL_RTO → balanceRatio (잔고 비율, %)
 *
 * @property ticker 종목코드 (예: "005930")
 * @property name 종목명 (예: "삼성전자")
 * @property balanceQuantity 공매도 잔고수량 (주)
 * @property balanceAmount 공매도 잔고금액 (원)
 * @property listedShares 상장주식수 (주)
 * @property balanceRatio 잔고 비율 (%)
 */
data class ShortBalance(
    val ticker: String,
    val name: String,
    val balanceQuantity: Long,
    val balanceAmount: Long,
    val listedShares: Long,
    val balanceRatio: Double?,
) {
    companion object {
        /**
         * KRX JSON 응답에서 ShortBalance 객체 생성
         *
         * @param json OutBlock_1 배열의 개별 JSON 객체
         * @return ShortBalance 또는 null (파싱 실패 시)
         */
        fun fromJson(json: JsonObject): ShortBalance? {
            return try {
                val ticker = json.get("ISU_SRT_CD")?.asString ?: return null

                ShortBalance(
                    ticker = ticker,
                    name = json.get("ISU_ABBRV")?.asString ?: "",
                    balanceQuantity = KrxJsonParser.parseLong(json.get("BAL_QTY")?.asString) ?: 0L,
                    balanceAmount = KrxJsonParser.parseLong(json.get("BAL_AMT")?.asString) ?: 0L,
                    listedShares = KrxJsonParser.parseLong(json.get("LIST_SHRS")?.asString) ?: 0L,
                    balanceRatio = KrxJsonParser.parseDouble(json.get("BAL_RTO")?.asString),
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * 잔고 비율 계산 (%)
     */
    val calculatedBalanceRatio: Double
        get() = if (listedShares > 0) (balanceQuantity.toDouble() / listedShares * 100) else 0.0
}

/**
 * 공매도 잔고 일별 추이 데이터 (개별종목)
 *
 * KRX API 응답 필드 매핑 (MDCSTAT30502):
 * - TRD_DD → date (거래일)
 * - BAL_QTY → balanceQuantity (잔고수량)
 * - BAL_AMT → balanceAmount (잔고금액)
 * - LIST_SHRS → listedShares (상장주식수)
 * - BAL_RTO → balanceRatio (잔고 비율, %)
 *
 * @property date 거래일 (yyyyMMdd)
 * @property balanceQuantity 공매도 잔고수량 (주)
 * @property balanceAmount 공매도 잔고금액 (원)
 * @property listedShares 상장주식수 (주)
 * @property balanceRatio 잔고 비율 (%)
 */
data class ShortBalanceHistory(
    val date: String,
    val balanceQuantity: Long,
    val balanceAmount: Long,
    val listedShares: Long,
    val balanceRatio: Double?,
) {
    companion object {
        /**
         * KRX JSON 응답에서 ShortBalanceHistory 객체 생성
         *
         * @param json OutBlock_1 배열의 개별 JSON 객체
         * @return ShortBalanceHistory 또는 null (파싱 실패 시)
         */
        fun fromJson(json: JsonObject): ShortBalanceHistory? {
            return try {
                val dateRaw = json.get("TRD_DD")?.asString ?: return null
                val date = dateRaw.replace("/", "")

                ShortBalanceHistory(
                    date = date,
                    balanceQuantity = KrxJsonParser.parseLong(json.get("BAL_QTY")?.asString) ?: 0L,
                    balanceAmount = KrxJsonParser.parseLong(json.get("BAL_AMT")?.asString) ?: 0L,
                    listedShares = KrxJsonParser.parseLong(json.get("LIST_SHRS")?.asString) ?: 0L,
                    balanceRatio = KrxJsonParser.parseDouble(json.get("BAL_RTO")?.asString),
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * 잔고 비율 계산 (%)
     */
    val calculatedBalanceRatio: Double
        get() = if (listedShares > 0) (balanceQuantity.toDouble() / listedShares * 100) else 0.0
}
