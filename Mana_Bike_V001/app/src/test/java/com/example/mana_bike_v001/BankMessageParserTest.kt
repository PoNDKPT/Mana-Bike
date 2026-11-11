package com.example.mana_bike_v001

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** ข้อความตัวอย่างในไฟล์นี้เป็นตัวอย่างสมมติ ยังไม่ใช่ข้อความจริงจากธนาคาร */
class BankMessageParserTest {

    @Test
    fun `incoming transfer returns the amount`() {
        assertEquals(500.0, BankMessageParser.parseIncoming("เงินเข้า 500.00 บาท บัญชี xxx-x-x1234-x")!!, 0.001)
    }

    @Test
    fun `outgoing transfer is ignored`() {
        assertNull(BankMessageParser.parseIncoming("โอนเงินออก 300.00 บาท จากบัญชี xxx-x-x1234-x"))
    }

    @Test
    fun `message without incoming keyword is ignored`() {
        assertNull(BankMessageParser.parseIncoming("ยอดเงินคงเหลือ 12,000.00 บาท"))
    }

    @Test
    fun `balance after the incoming amount is not picked`() {
        assertEquals(
            1250.5,
            BankMessageParser.parseIncoming("ยอดเงินคงเหลือ 9,999.00 บาท เงินเข้า 1,250.50 บาท")!!,
            0.001
        )
    }

    @Test
    fun `fee is not picked as the incoming amount`() {
        assertEquals(
            200.0,
            BankMessageParser.parseIncoming("ค่าธรรมเนียม 5.00 บาท รับโอน 200.00 บาท")!!,
            0.001
        )
    }

    @Test
    fun `baht sign prefix is parsed`() {
        assertEquals(89.0, BankMessageParser.parseIncoming("ได้รับเงิน ฿89")!!, 0.001)
    }

    @Test
    fun `english THB prefix message is parsed`() {
        assertEquals(1500.0, BankMessageParser.parseIncoming("Received THB 1,500.00 to a/c x1234")!!, 0.001)
    }

    @Test
    fun `incoming payment received via QR is not mistaken for outgoing`() {
        assertEquals(350.0, BankMessageParser.parseIncoming("รับชำระเงิน QR 350.00 บาท")!!, 0.001)
    }

    @Test
    fun `outgoing payment is still ignored`() {
        assertNull(BankMessageParser.parseIncoming("ชำระเงิน 350.00 บาท ร้าน xxx"))
    }

    @Test
    fun `message with no amount is ignored`() {
        assertNull(BankMessageParser.parseIncoming("มีเงินเข้าบัญชีของคุณ"))
    }
}
