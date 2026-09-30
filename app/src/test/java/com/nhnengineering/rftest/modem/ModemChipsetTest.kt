package com.nhnengineering.rftest.modem

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins [ModemChipset.classify]'s decision tree against constructed [ModemChipset.Signals] --
 * the pure half of the classifier. [ModemChipset.currentSignals] itself (real `Build.*` reads and
 * a file stat) is device-dependent and not covered here, the same way this project's other
 * `su`-adjacent collection isn't unit tested.
 */
class ModemChipsetTest {

    private fun signals(
        socManufacturer: String? = null,
        hardware: String = "",
        board: String = "",
        qrtrLookupPresent: Boolean = false,
    ) = ModemChipset.Signals(socManufacturer, hardware, board, qrtrLookupPresent)

    @Test
    fun `SOC_MANUFACTURER Qualcomm is trusted directly`() {
        assertEquals(ModemChipset.Vendor.QUALCOMM, ModemChipset.classify(signals(socManufacturer = "Qualcomm")))
    }

    @Test
    fun `SOC_MANUFACTURER Samsung is Exynos`() {
        assertEquals(ModemChipset.Vendor.EXYNOS, ModemChipset.classify(signals(socManufacturer = "Samsung")))
    }

    @Test
    fun `SOC_MANUFACTURER MediaTek is MediaTek`() {
        assertEquals(ModemChipset.Vendor.MEDIATEK, ModemChipset.classify(signals(socManufacturer = "MediaTek")))
    }

    @Test
    fun `SOC_MANUFACTURER Unisoc or Spreadtrum is Unisoc`() {
        assertEquals(ModemChipset.Vendor.UNISOC, ModemChipset.classify(signals(socManufacturer = "UNISOC")))
        assertEquals(ModemChipset.Vendor.UNISOC, ModemChipset.classify(signals(socManufacturer = "Spreadtrum")))
    }

    @Test
    fun `qrtr-lookup present with no SOC_MANUFACTURER falls back to Qualcomm`() {
        assertEquals(
            ModemChipset.Vendor.QUALCOMM,
            ModemChipset.classify(signals(qrtrLookupPresent = true)),
        )
    }

    @Test
    fun `qrtr-lookup absent proves nothing -- falls through to the codename heuristics`() {
        val result = ModemChipset.classify(signals(hardware = "qcom", qrtrLookupPresent = false))
        assertEquals(ModemChipset.Vendor.QUALCOMM, result)
    }

    @Test
    fun `hardware codename heuristics classify each vendor`() {
        assertEquals(ModemChipset.Vendor.QUALCOMM, ModemChipset.classify(signals(hardware = "qcom", board = "kona")))
        assertEquals(ModemChipset.Vendor.EXYNOS, ModemChipset.classify(signals(board = "s5e9925")))
        assertEquals(ModemChipset.Vendor.MEDIATEK, ModemChipset.classify(signals(hardware = "mt6983")))
        assertEquals(ModemChipset.Vendor.UNISOC, ModemChipset.classify(signals(board = "ums9230")))
    }

    @Test
    fun `nothing recognisable classifies as OTHER_OR_UNKNOWN`() {
        assertEquals(
            ModemChipset.Vendor.OTHER_OR_UNKNOWN,
            ModemChipset.classify(signals(hardware = "goldfish", board = "generic_x86")),
        )
    }

    @Test
    fun `SOC_MANUFACTURER wins over a conflicting hardware codename`() {
        // Fabricated conflict on purpose: SOC_MANUFACTURER must be trusted over the weaker heuristic.
        val result = ModemChipset.classify(signals(socManufacturer = "Samsung", hardware = "qcom", board = "kona"))
        assertEquals(ModemChipset.Vendor.EXYNOS, result)
    }
}
