package com.example.mana_bike_v001

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** ชื่อผู้ส่ง SMS ในไฟล์นี้เป็นตัวอย่างสมมติ */
class BankDetectTest {

    @Test
    fun `known package maps to its bank`() {
        assertEquals(Bank.KBANK, Bank.detect("com.kasikorn.retail.mbanking.wap", "", ""))
        assertEquals(Bank.KTB, Bank.detect("com.ktb.customer.qr", "", ""))
    }

    @Test
    fun `sms sender name maps to bank`() {
        assertEquals(Bank.SCB, Bank.detect(null, "", "SCB"))
        assertEquals(Bank.GSB, Bank.detect(null, "", "MyMo"))
    }

    @Test
    fun `unrelated app is not a bank`() {
        assertNull(Bank.detect("com.line.android", "LINE", "แม่"))
    }
}
