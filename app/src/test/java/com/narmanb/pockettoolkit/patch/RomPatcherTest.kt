package com.narmanb.pockettoolkit.patch

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

class RomPatcherTest {
    private val source = "ABC".encodeToByteArray()
    private val target = "AXC".encodeToByteArray()

    @Test
    fun appliesIpsPatch() {
        val patch = byteArrayOf(
            'P'.code.toByte(), 'A'.code.toByte(), 'T'.code.toByte(), 'C'.code.toByte(), 'H'.code.toByte(),
            0x00, 0x00, 0x01,
            0x00, 0x01,
            'X'.code.toByte(),
            'E'.code.toByte(), 'O'.code.toByte(), 'F'.code.toByte()
        )
        val result = RomPatcher.apply(source, patch)
        assertEquals(PatchFormat.IPS, result.format)
        assertArrayEquals(target, result.bytes)
    }

    @Test
    fun appliesBpsPatchAndChecksCrcs() {
        val patch = makeBpsLiteralPatch(source, target)
        val result = RomPatcher.apply(source, patch)
        assertEquals(PatchFormat.BPS, result.format)
        assertArrayEquals(target, result.bytes)
    }

    @Test
    fun appliesUpsPatchAndChecksCrcs() {
        val patch = makeUpsSingleDifferencePatch(source, target, 1)
        val result = RomPatcher.apply(source, patch)
        assertEquals(PatchFormat.UPS, result.format)
        assertArrayEquals(target, result.bytes)
    }

    private fun makeBpsLiteralPatch(source: ByteArray, target: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("BPS1".encodeToByteArray())
        writeVarInt(out, source.size.toLong())
        writeVarInt(out, target.size.toLong())
        writeVarInt(out, 0)
        val command = ((target.size - 1).toLong() shl 2) or 1L // TargetRead
        writeVarInt(out, command)
        out.write(target)
        writeLe32(out, crc(source))
        writeLe32(out, crc(target))
        val withoutPatchCrc = out.toByteArray()
        writeLe32(out, crc(withoutPatchCrc))
        return out.toByteArray()
    }

    private fun makeUpsSingleDifferencePatch(source: ByteArray, target: ByteArray, differenceOffset: Int): ByteArray {
        require(source.size == target.size)
        val out = ByteArrayOutputStream()
        out.write("UPS1".encodeToByteArray())
        writeVarInt(out, source.size.toLong())
        writeVarInt(out, target.size.toLong())
        writeVarInt(out, differenceOffset.toLong())
        out.write((source[differenceOffset].toInt() xor target[differenceOffset].toInt()) and 0xff)
        out.write(0)
        writeLe32(out, crc(source))
        writeLe32(out, crc(target))
        val withoutPatchCrc = out.toByteArray()
        writeLe32(out, crc(withoutPatchCrc))
        return out.toByteArray()
    }

    private fun writeVarInt(out: ByteArrayOutputStream, initial: Long) {
        var value = initial
        while (true) {
            var x = (value and 0x7f).toInt()
            value = value ushr 7
            if (value == 0L) {
                out.write(x or 0x80)
                return
            }
            out.write(x)
            value--
        }
    }

    private fun crc(bytes: ByteArray): Long = CRC32().run { update(bytes); value }

    private fun writeLe32(out: ByteArrayOutputStream, value: Long) {
        out.write((value and 0xff).toInt())
        out.write(((value ushr 8) and 0xff).toInt())
        out.write(((value ushr 16) and 0xff).toInt())
        out.write(((value ushr 24) and 0xff).toInt())
    }
}
