package com.branchconsole.app.collectors.krx

import com.krxkt.model.InvestorTrading
import com.krxkt.parser.KrxJsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * MT1-04c 계약 재확인(브리프 명시) — D-1(TRDVAL8~11 슬롯 오정렬) 수정이 `:app`이 실제로 쓰는
 * 소비 경로(`KrxJsonParser.parseOutBlock` -> `InvestorTrading.fromJson`)에서도 유효한지
 * 별도로 고정한다. `:krx`의 `InvestorTradingTest`에 이미 있는 D-1/D-2 witness와 픽스처는
 * 같지만(00c 저널 §3.4, 2026-08-05 mktId=ALL raw JSON), 검증 대상 모듈 경계가 다르다.
 */
class KrxInvestorTradingContractTest {
    @Test
    fun `foreigner net buy reproduces the D-1 witness value from raw all-scope json`() {
        val raw =
            """
            {"output": [
                {"TRD_DD":"2026/08/05",
                 "TRDVAL1":"-28,799,818,552","TRDVAL2":"-7,009,938,372","TRDVAL3":"165,150,622,478",
                 "TRDVAL4":"-511,707,108,428","TRDVAL5":"2,405,915,443","TRDVAL6":"21,332,394,103",
                 "TRDVAL7":"-35,201,697,491","TRDVAL8":"35,576,102,556","TRDVAL9":"-790,239,060,348",
                 "TRDVAL10":"1,154,143,165,996","TRDVAL11":"-5,650,577,385","TRDVAL_TOT":"0"}
            ]}
            """.trimIndent()

        val row = KrxJsonParser.parseOutBlock(raw).single()
        val trading = InvestorTrading.fromJson(row)

        assertEquals(1_148_492_588_611L, trading?.foreigner)
    }
}
