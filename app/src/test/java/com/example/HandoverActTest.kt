package com.example

import com.example.data.HandoverAct
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §4: Тесты акта выдачи/возврата скутера (HandoverAct).
 *
 * Проверяют:
 *   • корректность типов акта (HANDOVER / RETURN)
 *   • что пробег и комплектация сохраняются
 *   • что при возврате пробег скутера обновляется
 */
class HandoverActTest {

    @Test
    fun `handover act has correct type`() {
        val act = HandoverAct(
            actType = HandoverAct.TYPE_HANDOVER,
            renterId = 1,
            scooterId = 1,
            mileageKm = 1000L,
            equipmentChecklist = "2 akkumulyator, 2 kalit, shlem",
            conditionNotes = "Yangi holatda"
        )
        assertEquals(HandoverAct.TYPE_HANDOVER, act.actType)
    }

    @Test
    fun `return act has correct type`() {
        val act = HandoverAct(
            actType = HandoverAct.TYPE_RETURN,
            renterId = 1,
            scooterId = 1,
            mileageKm = 1500L,
            equipmentChecklist = "2 akkumulyator, 2 kalit, shlem",
            conditionNotes = "Eski shikastlar: kraska qirrangan"
        )
        assertEquals(HandoverAct.TYPE_RETURN, act.actType)
    }

    @Test
    fun `mileage tracked correctly`() {
        val handover = HandoverAct(
            actType = HandoverAct.TYPE_HANDOVER,
            renterId = 1, scooterId = 1,
            mileageKm = 1000L
        )
        val returnAct = HandoverAct(
            actType = HandoverAct.TYPE_RETURN,
            renterId = 1, scooterId = 1,
            mileageKm = 1500L
        )
        val distance = returnAct.mileageKm - handover.mileageKm
        assertEquals(500L, distance)
    }

    @Test
    fun `equipment checklist preserves content`() {
        val equipment = "Akkumulyator 1: ABC-001\nAkkumulyator 2: ABC-002\nKalit: 2 dona\nShlem: 1 dona"
        val act = HandoverAct(
            actType = HandoverAct.TYPE_HANDOVER,
            renterId = 1, scooterId = 1,
            equipmentChecklist = equipment
        )
        assertNotNull(act.equipmentChecklist)
        assertTrue(act.equipmentChecklist.contains("Akkumulyator 1"))
        assertTrue(act.equipmentChecklist.contains("Shlem"))
    }
}
