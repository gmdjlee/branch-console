package com.krxkt.model

import com.google.gson.JsonObject
import com.krxkt.parser.KrxJsonParser

/**
 * 투자자별 거래실적 데이터
 *
 * KRX API 응답 필드 매핑 — **전체시장 일별추이(MDCSTAT02203)와 개별종목 일별추이(MDCSTAT02303)는
 * 동일한 TRDVAL 슬롯 배치를 공유한다** (MT1-01g 벤더링 시 정정, branch-console
 * docs/journal/2026-08-07_MT1-00c_kotlin_krx.md §3.4 — pykrx 실측 대조로 확정):
 * - TRD_DD → date (거래일)
 * - TRDVAL1 → financialInvestment (금융투자)
 * - TRDVAL2 → insurance (보험)
 * - TRDVAL3 → investmentTrust (투신)
 * - TRDVAL4 → privateEquity (사모)
 * - TRDVAL5 → bank (은행)
 * - TRDVAL6 → otherFinance (기타금융)
 * - TRDVAL7 → pensionFund (연기금)
 * - TRDVAL8 → otherCorporation (기타법인, NOT 기관합계!)
 * - TRDVAL9 → individual (개인)
 * - TRDVAL10 → foreigner 주성분 (외국인)
 * - TRDVAL11 → foreigner 부속성분 (기타외국인)
 * - TRDVAL_TOT → total (전체, 11분류 배타 순매수의 합 — 참값은 0. 결측이 아니라 항등식이다)
 *
 * institutionalTotal(기관합계) = TRDVAL1 + ... + TRDVAL7 (직접 합산, 응답에 별도 필드 없음)
 * foreigner(외국인합계) = TRDVAL10 + TRDVAL11 (외국인 + 기타외국인)
 *
 * ⚠️ 원래 upstream `fromJson()`은 TRDVAL8~11을 institutionalTotal/otherCorporation/individual/
 * foreigner로 한 칸씩 밀어서 가정했다(D-1, 벤더링 전 결함) — `fromTickerJson()`의 KDoc은 처음부터
 * 정확했다. 두 파서가 실제로는 동일 로직이므로 `fromJson()`은 `fromTickerJson()`에 위임한다.
 *
 * @property date 거래일 (yyyyMMdd)
 * @property financialInvestment 금융투자 거래대금/거래량
 * @property insurance 보험 거래대금/거래량
 * @property investmentTrust 투신 거래대금/거래량
 * @property privateEquity 사모 거래대금/거래량
 * @property bank 은행 거래대금/거래량
 * @property otherFinance 기타금융 거래대금/거래량
 * @property pensionFund 연기금 거래대금/거래량
 * @property institutionalTotal 기관합계 거래대금/거래량
 * @property otherCorporation 기타법인 거래대금/거래량
 * @property individual 개인 거래대금/거래량
 * @property foreigner 외국인 거래대금/거래량
 * @property total 전체 거래대금/거래량
 */
data class InvestorTrading(
    val date: String,
    val financialInvestment: Long,
    val insurance: Long,
    val investmentTrust: Long,
    val privateEquity: Long,
    val bank: Long,
    val otherFinance: Long,
    val pensionFund: Long,
    val institutionalTotal: Long,
    val otherCorporation: Long,
    val individual: Long,
    val foreigner: Long,
    val total: Long,
) {
    companion object {
        /**
         * KRX JSON 응답에서 InvestorTrading 객체 생성 (전체시장 일별 추이, MDCSTAT02203)
         *
         * TRDVAL8~11 슬롯은 개별종목 응답(MDCSTAT02303)과 동일 배치를 공유한다(D-1 정정,
         * class KDoc 참고) — [fromTickerJson]에 그대로 위임한다.
         *
         * @param json OutBlock_1 배열의 개별 JSON 객체
         * @return InvestorTrading 또는 null (파싱 실패 시)
         */
        fun fromJson(json: JsonObject): InvestorTrading? = fromTickerJson(json)

        /**
         * KRX JSON 응답에서 InvestorTrading 객체 생성 (개별종목·전체시장 일별추이 공용,
         * MDCSTAT02303/MDCSTAT02203 — 두 엔드포인트가 동일 슬롯 배치를 쓴다, class KDoc 참고)
         *
         * - TRDVAL1~7: 기관 세부항목 (금융투자, 보험, 투신, 사모, 은행, 기타금융, 연기금)
         * - TRDVAL8:  기타법인 (NOT 기관합계!)
         * - TRDVAL9:  개인
         * - TRDVAL10: 외국인
         * - TRDVAL11: 기타외국인
         *
         * 기관합계 = TRDVAL1 + ... + TRDVAL7 (직접 합산)
         * 외국인합계 = TRDVAL10 + TRDVAL11 (외국인 + 기타외국인)
         *
         * @param json OutBlock_1 배열의 개별 JSON 객체
         * @return InvestorTrading 또는 null (파싱 실패 시)
         */
        fun fromTickerJson(json: JsonObject): InvestorTrading? {
            return try {
                val dateRaw = json.get("TRD_DD")?.asString ?: return null
                val date = dateRaw.replace("/", "")

                val financialInvestment = KrxJsonParser.parseLong(json.get("TRDVAL1")?.asString) ?: 0L
                val insurance = KrxJsonParser.parseLong(json.get("TRDVAL2")?.asString) ?: 0L
                val investmentTrust = KrxJsonParser.parseLong(json.get("TRDVAL3")?.asString) ?: 0L
                val privateEquity = KrxJsonParser.parseLong(json.get("TRDVAL4")?.asString) ?: 0L
                val bank = KrxJsonParser.parseLong(json.get("TRDVAL5")?.asString) ?: 0L
                val otherFinance = KrxJsonParser.parseLong(json.get("TRDVAL6")?.asString) ?: 0L
                val pensionFund = KrxJsonParser.parseLong(json.get("TRDVAL7")?.asString) ?: 0L

                // 개별종목: TRDVAL8 = 기타법인, TRDVAL9 = 개인, TRDVAL10 = 외국인, TRDVAL11 = 기타외국인
                val otherCorporation = KrxJsonParser.parseLong(json.get("TRDVAL8")?.asString) ?: 0L
                val individual = KrxJsonParser.parseLong(json.get("TRDVAL9")?.asString) ?: 0L
                val foreignerMain = KrxJsonParser.parseLong(json.get("TRDVAL10")?.asString) ?: 0L
                val foreignerOther = KrxJsonParser.parseLong(json.get("TRDVAL11")?.asString) ?: 0L

                // 기관합계 = 기관 세부항목 합산
                val institutionalTotal =
                    financialInvestment + insurance + investmentTrust +
                        privateEquity + bank + otherFinance + pensionFund
                // 외국인합계 = 외국인 + 기타외국인
                val foreigner = foreignerMain + foreignerOther

                InvestorTrading(
                    date = date,
                    financialInvestment = financialInvestment,
                    insurance = insurance,
                    investmentTrust = investmentTrust,
                    privateEquity = privateEquity,
                    bank = bank,
                    otherFinance = otherFinance,
                    pensionFund = pensionFund,
                    institutionalTotal = institutionalTotal,
                    otherCorporation = otherCorporation,
                    individual = individual,
                    foreigner = foreigner,
                    total = KrxJsonParser.parseLong(json.get("TRDVAL_TOT")?.asString) ?: 0L,
                )
            } catch (e: Exception) {
                null
            }
        }

        /**
         * KRX JSON 응답에서 InvestorTrading 객체 생성 (기간합계용)
         * 기간합계는 INVST_TP_NM(투자자명) 컬럼이 있고 행 단위로 투자자별 데이터가 옴
         *
         * @param jsonList OutBlock_1 배열 전체
         * @param date 기준 날짜
         * @return InvestorTrading
         */
        fun fromPeriodJson(
            jsonList: List<JsonObject>,
            date: String,
        ): InvestorTrading {
            var financialInvestment = 0L
            var insurance = 0L
            var investmentTrust = 0L
            var privateEquity = 0L
            var bank = 0L
            var otherFinance = 0L
            var pensionFund = 0L
            var otherCorporation = 0L
            var individual = 0L
            var foreigner = 0L
            var total = 0L

            jsonList.forEach { json ->
                val investorName = json.get("INVST_TP_NM")?.asString ?: return@forEach
                // 순매수 = 매수 - 매도 (NETBID_TRDVAL 필드 또는 계산)
                val value = KrxJsonParser.parseLong(json.get("NETBID_TRDVAL")?.asString) ?: 0L

                when {
                    investorName.contains("금융투자") -> financialInvestment = value
                    investorName.contains("보험") -> insurance = value
                    investorName.contains("투신") -> investmentTrust = value
                    investorName.contains("사모") -> privateEquity = value
                    investorName.contains("은행") -> bank = value
                    investorName.contains("기타금융") -> otherFinance = value
                    investorName.contains("연기금") -> pensionFund = value
                    investorName.contains("기타법인") -> otherCorporation = value
                    investorName.contains("개인") -> individual = value
                    investorName.contains("외국인") && !investorName.contains("기타") -> foreigner = value
                    investorName.contains("합계") || investorName.contains("전체") -> total = value
                }
            }

            val institutionalTotal =
                financialInvestment + insurance + investmentTrust +
                    privateEquity + bank + otherFinance + pensionFund

            return InvestorTrading(
                date = date,
                financialInvestment = financialInvestment,
                insurance = insurance,
                investmentTrust = investmentTrust,
                privateEquity = privateEquity,
                bank = bank,
                otherFinance = otherFinance,
                pensionFund = pensionFund,
                institutionalTotal = institutionalTotal,
                otherCorporation = otherCorporation,
                individual = individual,
                foreigner = foreigner,
                total = total,
            )
        }
    }

    /**
     * 기관 순매수 합계
     */
    val institutionalNetBuy: Long get() = institutionalTotal

    /**
     * 외국인 순매수
     */
    val foreignerNetBuy: Long get() = foreigner

    /**
     * 개인 순매수
     */
    val individualNetBuy: Long get() = individual
}

/**
 * 투자자 유형
 */
enum class InvestorType(val code: Int, val korName: String) {
    FINANCIAL_INVESTMENT(1000, "금융투자"),
    INSURANCE(2000, "보험"),
    INVESTMENT_TRUST(3000, "투신"),
    PRIVATE_EQUITY(3100, "사모"),
    BANK(4000, "은행"),
    OTHER_FINANCE(5000, "기타금융"),
    PENSION_FUND(6000, "연기금"),
    INSTITUTIONAL_TOTAL(7050, "기관합계"),
    OTHER_CORPORATION(7100, "기타법인"),
    INDIVIDUAL(8000, "개인"),
    FOREIGNER(9000, "외국인"),
    OTHER_FOREIGNER(9001, "기타외국인"),
    TOTAL(9999, "전체"),
    ;

    companion object {
        fun fromCode(code: Int): InvestorType? = entries.find { it.code == code }

        fun fromName(name: String): InvestorType? = entries.find { name.contains(it.korName) }
    }
}

/**
 * 거래 유형 (거래량/거래대금)
 */
enum class TradingValueType(val code: String) {
    VOLUME("1"), // 거래량
    VALUE("2"), // 거래대금
}

/**
 * 매수/매도 유형
 */
enum class AskBidType(val code: String) {
    SELL("1"), // 매도
    BUY("2"), // 매수
    NET_BUY("3"), // 순매수
}
