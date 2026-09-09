package kr.co.tesla.cameraalert.data

import org.junit.Assert.*
import org.junit.Test

class CsvTest {
    @Test fun quotedRoadNamesNewlinesAndEscapedQuotes() {
        val rows = Csv.parse("위도,도로명\r\n37.5,\"도로, 이름\"\r\n37.6,\"첫째\n둘째\"\"길\"\r\n")
        assertEquals(3, rows.size)
        assertEquals("도로, 이름", rows[1][1])
        assertEquals("첫째\n둘째\"길", rows[2][1])
    }
    @Test(expected = IllegalArgumentException::class) fun unterminatedQuoteFails() {
        Csv.parse("위도,도로명\n37.5,\"도로")
    }
    @Test fun blankLinesAndTrailingEmptyCell() {
        assertEquals(listOf(listOf("a", "b", "")), Csv.parse("\r\na,b,\r\n"))
    }
}

