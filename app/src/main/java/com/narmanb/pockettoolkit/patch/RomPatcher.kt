package com.narmanb.pockettoolkit.patch

import java.util.zip.CRC32

internal enum class PatchFormat { IPS, BPS, UPS }

internal data class PatchResult(
    val bytes: ByteArray,
    val format: PatchFormat
)

internal object RomPatcher {
    fun detectFormat(patch: ByteArray): PatchFormat = when {
        patch.startsWithAscii("PATCH") -> PatchFormat.IPS
        patch.startsWithAscii("BPS1") -> PatchFormat.BPS
        patch.startsWithAscii("UPS1") -> PatchFormat.UPS
        else -> error("Unsupported patch format. Pocket Toolkit currently supports IPS, BPS, and UPS.")
    }

    fun apply(source: ByteArray, patch: ByteArray): PatchResult {
        val format = detectFormat(patch)
        val output = when (format) {
            PatchFormat.IPS -> applyIps(source, patch)
            PatchFormat.BPS -> applyBps(source, patch)
            PatchFormat.UPS -> applyUps(source, patch)
        }
        return PatchResult(output, format)
    }

    private fun applyIps(source: ByteArray, patch: ByteArray): ByteArray {
        require(patch.size >= 8 && patch.startsWithAscii("PATCH")) { "Invalid IPS patch." }
        var target = source.copyOf()
        var cursor = 5

        while (true) {
            require(cursor + 3 <= patch.size) { "Truncated IPS patch." }
            if (patch[cursor] == 'E'.code.toByte() && patch[cursor + 1] == 'O'.code.toByte() && patch[cursor + 2] == 'F'.code.toByte()) {
                cursor += 3
                if (cursor + 3 <= patch.size) {
                    val truncateTo = readBe24(patch, cursor)
                    target = target.copyOf(truncateTo)
                }
                return target
            }

            val offset = readBe24(patch, cursor)
            cursor += 3
            require(cursor + 2 <= patch.size) { "Truncated IPS record." }
            val length = readBe16(patch, cursor)
            cursor += 2

            if (length == 0) {
                require(cursor + 3 <= patch.size) { "Truncated IPS RLE record." }
                val runLength = readBe16(patch, cursor)
                cursor += 2
                val value = patch[cursor++]
                target = ensureCapacity(target, offset + runLength)
                for (i in 0 until runLength) target[offset + i] = value
            } else {
                require(cursor + length <= patch.size) { "Truncated IPS data record." }
                target = ensureCapacity(target, offset + length)
                patch.copyInto(target, offset, cursor, cursor + length)
                cursor += length
            }
        }
    }

    private fun applyBps(source: ByteArray, patch: ByteArray): ByteArray {
        require(patch.size >= 16 && patch.startsWithAscii("BPS1")) { "Invalid BPS patch." }
        verifyPatchCrc(patch)
        val reader = VarIntReader(patch, 4, patch.size - 12)
        val sourceSize = reader.readVarInt().toIntChecked("BPS source size")
        val targetSize = reader.readVarInt().toIntChecked("BPS target size")
        require(sourceSize == source.size) { "Wrong source ROM: BPS expects $sourceSize bytes, selected file is ${source.size} bytes." }

        val metadataSize = reader.readVarInt().toIntChecked("BPS metadata size")
        reader.skip(metadataSize)

        val target = ByteArray(targetSize)
        var outputOffset = 0
        var sourceRelativeOffset = 0L
        var targetRelativeOffset = 0L

        while (outputOffset < targetSize) {
            val command = reader.readVarInt()
            val action = (command and 3L).toInt()
            val length = ((command ushr 2) + 1L).toIntChecked("BPS command length")
            require(outputOffset + length <= targetSize) { "BPS command writes past target size." }

            when (action) {
                0 -> { // SourceRead: source position follows target position.
                    require(outputOffset + length <= source.size) { "BPS SourceRead exceeds source ROM." }
                    source.copyInto(target, outputOffset, outputOffset, outputOffset + length)
                    outputOffset += length
                }
                1 -> { // TargetRead: literal bytes stored in patch.
                    reader.copyTo(target, outputOffset, length)
                    outputOffset += length
                }
                2 -> { // SourceCopy: signed relative source offset.
                    sourceRelativeOffset += decodeSigned(reader.readVarInt())
                    require(sourceRelativeOffset >= 0 && sourceRelativeOffset + length <= source.size) { "BPS SourceCopy exceeds source ROM." }
                    source.copyInto(
                        target,
                        outputOffset,
                        sourceRelativeOffset.toInt(),
                        sourceRelativeOffset.toInt() + length
                    )
                    sourceRelativeOffset += length
                    outputOffset += length
                }
                3 -> { // TargetCopy: may intentionally overlap already-written output.
                    targetRelativeOffset += decodeSigned(reader.readVarInt())
                    require(targetRelativeOffset >= 0 && targetRelativeOffset < targetSize) { "BPS TargetCopy has invalid offset." }
                    repeat(length) {
                        require(targetRelativeOffset >= 0 && targetRelativeOffset < outputOffset) { "BPS TargetCopy references unwritten output." }
                        target[outputOffset++] = target[targetRelativeOffset.toInt()]
                        targetRelativeOffset++
                    }
                }
            }
        }

        verifySourceAndTargetCrc(source, target, patch)
        return target
    }

    private fun applyUps(source: ByteArray, patch: ByteArray): ByteArray {
        require(patch.size >= 16 && patch.startsWithAscii("UPS1")) { "Invalid UPS patch." }
        verifyPatchCrc(patch)
        val reader = VarIntReader(patch, 4, patch.size - 12)
        val sourceSize = reader.readVarInt().toIntChecked("UPS source size")
        val targetSize = reader.readVarInt().toIntChecked("UPS target size")
        require(sourceSize == source.size) { "Wrong source ROM: UPS expects $sourceSize bytes, selected file is ${source.size} bytes." }

        val target = source.copyOf(targetSize)
        var relativeOffset = 0L

        while (reader.hasRemaining()) {
            relativeOffset += reader.readVarInt()
            require(relativeOffset >= 0 && relativeOffset < targetSize) { "UPS patch offset exceeds target ROM." }

            while (true) {
                val xor = reader.readByte()
                if (xor == 0) {
                    relativeOffset++
                    break
                }
                require(relativeOffset >= 0 && relativeOffset < targetSize) { "UPS patch writes past target ROM." }
                val sourceByte = if (relativeOffset < source.size) source[relativeOffset.toInt()].toInt() and 0xff else 0
                target[relativeOffset.toInt()] = (sourceByte xor xor).toByte()
                relativeOffset++
            }
        }

        verifySourceAndTargetCrc(source, target, patch)
        return target
    }

    private fun verifyPatchCrc(patch: ByteArray) {
        val expected = readLe32(patch, patch.size - 4)
        val actual = crc32(patch, 0, patch.size - 4)
        require(actual == expected) { "Patch CRC32 check failed; the patch file may be corrupt." }
    }

    private fun verifySourceAndTargetCrc(source: ByteArray, target: ByteArray, patch: ByteArray) {
        val expectedSource = readLe32(patch, patch.size - 12)
        val expectedTarget = readLe32(patch, patch.size - 8)
        require(crc32(source, 0, source.size) == expectedSource) { "Source ROM CRC32 does not match this patch." }
        require(crc32(target, 0, target.size) == expectedTarget) { "Patched ROM CRC32 verification failed." }
    }

    private fun ensureCapacity(bytes: ByteArray, required: Int): ByteArray =
        if (required <= bytes.size) bytes else bytes.copyOf(required)

    private fun decodeSigned(value: Long): Long {
        val magnitude = value ushr 1
        return if ((value and 1L) != 0L) -magnitude else magnitude
    }

    private fun ByteArray.startsWithAscii(value: String): Boolean {
        if (size < value.length) return false
        return value.indices.all { this[it].toInt() and 0xff == value[it].code }
    }

    private fun readBe16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private fun readBe24(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 16) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            (bytes[offset + 2].toInt() and 0xff)

    private fun readLe32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xffL) or
            ((bytes[offset + 1].toLong() and 0xffL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xffL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xffL) shl 24)

    private fun crc32(bytes: ByteArray, offset: Int, length: Int): Long =
        CRC32().run {
            update(bytes, offset, length)
            value
        }

    private fun Long.toIntChecked(label: String): Int {
        require(this in 0..Int.MAX_VALUE.toLong()) { "$label is too large for this version of Pocket Toolkit." }
        return toInt()
    }

    private class VarIntReader(
        private val bytes: ByteArray,
        start: Int,
        private val limit: Int
    ) {
        private var cursor = start

        fun hasRemaining(): Boolean = cursor < limit

        fun readByte(): Int {
            require(cursor < limit) { "Unexpected end of patch data." }
            return bytes[cursor++].toInt() and 0xff
        }

        fun readVarInt(): Long {
            var value = 0L
            var shift = 1L
            while (true) {
                val x = readByte()
                value += (x and 0x7f).toLong() * shift
                if ((x and 0x80) != 0) return value
                require(shift <= (Long.MAX_VALUE ushr 7)) { "Patch integer is too large." }
                shift = shift shl 7
                value += shift
            }
        }

        fun skip(count: Int) {
            require(count >= 0 && cursor + count <= limit) { "Patch metadata is truncated." }
            cursor += count
        }

        fun copyTo(target: ByteArray, targetOffset: Int, count: Int) {
            require(count >= 0 && cursor + count <= limit) { "Patch literal data is truncated." }
            bytes.copyInto(target, targetOffset, cursor, cursor + count)
            cursor += count
        }
    }
}
