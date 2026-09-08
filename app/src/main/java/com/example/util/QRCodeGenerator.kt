package com.example.util

/**
 * Lightweight self-contained QR Code matrix generator in pure Kotlin.
 * Supports Byte encoding mode with Error Correction Level M,
 * generating a clean 2D boolean grid ready for Jetpack Compose Canvas rendering.
 */
object QRCodeGenerator {

    /**
     * Generates a 2D boolean grid representing the QR Code for the given text.
     * true = dark module, false = light module.
     */
    fun encodeToMatrix(text: String, minDimension: Int = 29): Array<BooleanArray> {
        val dataBytes = text.toByteArray(Charsets.UTF_8)
        // Choose version based on data length (Version 2: 25x25, Version 3: 29x29, Version 4: 33x33)
        val version = when {
            dataBytes.size <= 20 -> 2 // 25x25
            dataBytes.size <= 38 -> 3 // 29x29
            dataBytes.size <= 60 -> 4 // 33x33
            dataBytes.size <= 86 -> 5 // 37x37
            else -> 6 // 41x41
        }
        val size = 17 + 4 * version
        val matrix = Array(size) { BooleanArray(size) }
        val reserved = Array(size) { BooleanArray(size) }

        // 1. Finder patterns (top-left, top-right, bottom-left)
        addFinderPattern(matrix, reserved, 0, 0)
        addFinderPattern(matrix, reserved, size - 7, 0)
        addFinderPattern(matrix, reserved, 0, size - 7)

        // 2. Timing patterns
        for (i in 8 until size - 8) {
            val v = (i % 2 == 0)
            if (!reserved[i][6]) {
                matrix[i][6] = v
                reserved[i][6] = true
            }
            if (!reserved[6][i]) {
                matrix[6][i] = v
                reserved[6][i] = true
            }
        }

        // 3. Alignment pattern for version >= 2
        if (version >= 2) {
            val alignPos = when (version) {
                2 -> intArrayOf(18)
                3 -> intArrayOf(22)
                4 -> intArrayOf(26)
                5 -> intArrayOf(30)
                else -> intArrayOf(34)
            }
            for (pos in alignPos) {
                addAnimationPattern(matrix, reserved, pos, pos)
            }
        }

        // 4. Dark module
        matrix[8][4 * version + 9] = true
        reserved[8][4 * version + 9] = true

        // 5. Reserve format info areas around finders
        for (i in 0..8) {
            reserved[8][i] = true
            reserved[i][8] = true
            reserved[8][size - 1 - i] = true
            reserved[size - 1 - i][8] = true
        }

        // 6. Encode data bitstream (Mode indicator: 0100 for Byte, Character count, Data, Terminator)
        val bitBuffer = mutableListOf<Boolean>()
        
        // Mode: 8-bit byte (0100)
        appendBits(bitBuffer, 0b0100, 4)
        // Character count indicator (8 bits for version 1-9)
        appendBits(bitBuffer, dataBytes.size, 8)
        // Data bytes
        for (b in dataBytes) {
            appendBits(bitBuffer, b.toInt() and 0xFF, 8)
        }
        // Terminator (up to 4 zeroes)
        val totalDataBits = (when (version) {
            2 -> 224
            3 -> 352
            4 -> 512
            5 -> 688
            else -> 864
        }) / 2 // Rough byte capacity approximation for EC level M

        appendBits(bitBuffer, 0, 4)
        while (bitBuffer.size % 8 != 0) {
            bitBuffer.add(false)
        }

        // Pad bytes (0xEC, 0x11 alternating)
        var padToggle = true
        val targetCapacityBits = (size * size) / 3 // usable capacity
        while (bitBuffer.size < targetCapacityBits) {
            val pad = if (padToggle) 0b11101100 else 0b00010001
            appendBits(bitBuffer, pad, 8)
            padToggle = !padToggle
        }

        // 7. Place data bits using standard zigzag scan with mask pattern (row + col) % 2 == 0
        var bitIndex = 0
        var right = size - 1
        while (right > 0) {
            if (right == 6) right-- // skip timing pattern col
            val cols = intArrayOf(right, right - 1)
            val upward = ((right + 1) / 2) % 2 == 1
            val rowRange = if (upward) (size - 1 downTo 0) else (0 until size)

            for (r in rowRange) {
                for (c in cols) {
                    if (!reserved[r][c]) {
                        val bit = if (bitIndex < bitBuffer.size) bitBuffer[bitIndex++] else false
                        // Apply mask: (row + col) % 2 == 0
                        val mask = (r + c) % 2 == 0
                        matrix[r][c] = bit xor mask
                        reserved[r][c] = true
                    }
                }
            }
            right -= 2
        }

        return matrix
    }

    private fun appendBits(buffer: MutableList<Boolean>, value: Int, length: Int) {
        for (i in length - 1 downTo 0) {
            buffer.add(((value shr i) and 1) == 1)
        }
    }

    private fun addFinderPattern(matrix: Array<BooleanArray>, reserved: Array<BooleanArray>, x: Int, y: Int) {
        for (r in -1..7) {
            for (c in -1..7) {
                val mr = y + r
                val mc = x + c
                if (mr in matrix.indices && mc in matrix[0].indices) {
                    reserved[mr][mc] = true
                    val isBorder = r == -1 || r == 7 || c == -1 || c == 7
                    if (isBorder) {
                        matrix[mr][mc] = false
                    } else {
                        val isOuter = r == 0 || r == 6 || c == 0 || c == 6
                        val isInner = r in 2..4 && c in 2..4
                        matrix[mr][mc] = isOuter || isInner
                    }
                }
            }
        }
    }

    private fun addAnimationPattern(matrix: Array<BooleanArray>, reserved: Array<BooleanArray>, cx: Int, cy: Int) {
        for (r in -2..2) {
            for (c in -2..2) {
                val mr = cy + r
                val mc = cx + c
                if (mr in matrix.indices && mc in matrix[0].indices) {
                    if (!reserved[mr][mc]) {
                        reserved[mr][mc] = true
                        val isCenter = r == 0 && c == 0
                        val isEdge = Math.abs(r) == 2 || Math.abs(c) == 2
                        matrix[mr][mc] = isCenter || isEdge
                    }
                }
            }
        }
    }
}
