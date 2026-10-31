package org.pspp.mobile.io

import org.pspp.mobile.model.CsvColumnPreview
import org.pspp.mobile.model.CsvImportPreviewState
import org.pspp.mobile.model.MeasureLevel
import org.pspp.mobile.model.PsppDataset
import org.pspp.mobile.model.PsppVariable
import org.pspp.mobile.model.VariableType
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Genuine binary SPSS/PSPP System File ($FL2 .sav) reader and writer, plus
 * RFC-4180 CSV / delimited text parser and column-type inference engine.
 */
object SavBinaryCodec {

    private const val SYSMIS_IEEE = -1.7976931348623157E308
    private const val DEFAULT_BIAS = 100.0

    /**
     * Writes a [PsppDataset] as a binary SPSS/PSPP System File ($FL2 .sav).
     * Produces a standards-compliant little-endian $FL2 header, Type 2 variable records,
     * Type 3/4 value label records, Type 999 dictionary terminator, and uncompressed
     * 8-byte IEEE-754 / 8-char slot case data readable by GNU PSPP and SPSS.
     */
    fun writeSavBytes(dataset: PsppDataset): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN)

        fun writeFixedAscii(str: String, len: Int) {
            val bytes = str.toByteArray(StandardCharsets.UTF_8)
            val padded = ByteArray(len) { ' '.code.toByte() }
            System.arraycopy(bytes, 0, padded, 0, minOf(bytes.size, len))
            out.write(padded)
        }

        fun writeInt32(v: Int) {
            buf.clear()
            buf.putInt(v)
            out.write(buf.array(), 0, 4)
        }

        fun writeFloat64(v: Double) {
            buf.clear()
            buf.putDouble(v)
            out.write(buf.array(), 0, 8)
        }

        val vars = dataset.variables
        val nominalCaseSize = vars.size // 1 8-byte slot per variable

        // 1. Record Type 1: 176-byte $FL2 File Header
        writeFixedAscii("\$FL2", 4)
        writeFixedAscii("@(#) SPSS DATA FILE - GNU PSPP 2.1.2 Android Studio", 60)
        writeInt32(2) // layout_code = 2 (little-endian IEEE 754)
        writeInt32(nominalCaseSize) // nominal_case_size (number of 8-byte elements per case)
        writeInt32(0) // compression = 0 (uncompressed IEEE-754 for maximum compatibility)
        writeInt32(0) // weight_index = 0 (unweighted)
        writeInt32(dataset.rows.size) // ncases
        writeFloat64(DEFAULT_BIAS) // bias = 100.0
        writeFixedAscii("31 Oct 26", 9) // creation_date
        writeFixedAscii("08:10:00", 8) // creation_time
        writeFixedAscii(dataset.name.take(64), 64) // file_label
        out.write(ByteArray(3)) // 3-byte padding to align header to 176 bytes

        // 2. Record Type 2: Variable Records (with unique 8-byte short names)
        val usedShortNames = mutableSetOf<String>()
        val shortToLongPairs = mutableListOf<Pair<String, String>>()

        vars.forEachIndexed { idx, v ->
            writeInt32(2) // rec_type = 2
            val isString = v.type == VariableType.STRING
            writeInt32(if (isString) 8 else 0) // 0 = numeric, >0 = string length (8 bytes)
            val hasLabel = v.label.isNotBlank()
            writeInt32(if (hasLabel) 1 else 0)
            writeInt32(0) // n_missing_values = 0
            // print & write format: (decimals) | (width << 8) | (typeCode << 16)
            val fmtCode = if (isString) 1 else 5 // 1 = A (alphanumeric), 5 = F (numeric)
            val fmtWord = (v.decimals and 0xFF) or ((v.width.coerceIn(1, 40) and 0xFF) shl 8) or (fmtCode shl 16)
            writeInt32(fmtWord)
            writeInt32(fmtWord)
            val baseShort = v.name.uppercase().replace(Regex("[^A-Z0-9_@#\$]"), "_").take(8).ifEmpty { "V${idx + 1}" }
            var shortName = baseShort
            var suffix = 1
            while (!usedShortNames.add(shortName)) {
                val sfx = "_$suffix"
                shortName = baseShort.take((8 - sfx.length).coerceAtLeast(1)) + sfx
                suffix++
            }
            shortToLongPairs.add(shortName to v.name)
            writeFixedAscii(shortName, 8)
            if (hasLabel) {
                val labelBytes = v.label.toByteArray(StandardCharsets.UTF_8)
                val labelLen = minOf(labelBytes.size, 120)
                val paddedLen = ((labelLen + 3) / 4) * 4
                writeInt32(labelLen)
                val padded = ByteArray(paddedLen) { ' '.code.toByte() }
                System.arraycopy(labelBytes, 0, padded, 0, labelLen)
                out.write(padded)
            }
        }

        // 3. Record Type 3 & 4: Value Label Records for numeric variables
        vars.forEachIndexed { idx, v ->
            if (v.valueLabels.isNotEmpty() && v.type != VariableType.STRING) {
                val numericEntries = v.valueLabels.mapNotNull { (k, label) ->
                    k.toDoubleOrNull()?.let { it to label }
                }
                if (numericEntries.isNotEmpty()) {
                    writeInt32(3) // rec_type = 3 (value labels)
                    writeInt32(numericEntries.size)
                    numericEntries.forEach { (numVal, labelText) ->
                        writeFloat64(numVal)
                        val lBytes = labelText.toByteArray(StandardCharsets.UTF_8)
                        val lLen = minOf(lBytes.size, 60)
                        // Total 1 + lLen must be multiple of 8 bytes
                        val totalLen = ((1 + lLen + 7) / 8) * 8
                        out.write(lLen)
                        val padded = ByteArray(totalLen - 1) { ' '.code.toByte() }
                        System.arraycopy(lBytes, 0, padded, 0, lLen)
                        out.write(padded)
                    }
                    // Record Type 4: Variable index list (1-based)
                    writeInt32(4)
                    writeInt32(1)
                    writeInt32(idx + 1)
                }
            }
        }

        // 3b. Record Type 7, Subtype 13: Long Variable Names Record (GNU PSPP / SPSS standard)
        if (shortToLongPairs.isNotEmpty()) {
            val longNameMapStr = shortToLongPairs.joinToString("\t") { (shortName, longName) ->
                "$shortName=$longName"
            }
            val mapBytes = longNameMapStr.toByteArray(StandardCharsets.UTF_8)
            writeInt32(7)          // rec_type = 7 (extension record)
            writeInt32(13)         // subtype = 13 (long variable names)
            writeInt32(1)          // size = 1 byte
            writeInt32(mapBytes.size) // count = byte length
            out.write(mapBytes)
        }

        // 4. Record Type 999: Dictionary Termination Record
        writeInt32(999)
        writeInt32(0)

        // 5. Case Data Stream
        dataset.rows.forEach { row ->
            vars.forEachIndexed { colIdx, v ->
                val cell = row.getOrNull(colIdx)?.trim().orEmpty()
                if (v.type == VariableType.STRING) {
                    writeFixedAscii(cell, 8)
                } else {
                    val d = cell.toDoubleOrNull() ?: SYSMIS_IEEE
                    writeFloat64(d)
                }
            }
        }

        return out.toByteArray()
    }

    /**
     * Reads a binary SPSS/PSPP System File ($FL2 .sav) into a [PsppDataset].
     * Supports both uncompressed (compression=0) and bytecode-compressed (compression=1) files.
     */
    fun readSavBytes(bytes: ByteArray, datasetName: String): PsppDataset {
        require(bytes.size >= 176) { "Invalid .sav file: smaller than 176-byte \$FL2 header" }
        val magic = String(bytes, 0, 4, StandardCharsets.US_ASCII)
        require(magic == "\$FL2" || magic == "\$FL3") { "Unsupported .sav signature: $magic (expected \$FL2)" }

        // Detect endianness via layout_code at offset 64
        val littleCheck = ByteBuffer.wrap(bytes, 64, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val order = if (littleCheck == 2 || littleCheck == 3) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        val buf = ByteBuffer.wrap(bytes).order(order)

        buf.position(68)
        val nominalCaseSize = buf.int
        val compression = buf.int
        buf.int // weight_index
        val ncasesHeader = buf.int
        val bias = buf.double
        buf.position(176)

        val variables = mutableListOf<PsppVariable>()
        val slotIsStringContinuation = mutableListOf<Boolean>()
        val pendingLabels = mutableListOf<Map<String, String>>()

        loop@ while (buf.remaining() >= 4) {
            val recType = buf.int
            when (recType) {
                2 -> {
                    val varTypeCode = buf.int
                    val hasLabel = buf.int
                    val nMissing = buf.int
                    val printFmt = buf.int
                    buf.int // writeFmt
                    val nameBytes = ByteArray(8)
                    buf.get(nameBytes)
                    val rawName = String(nameBytes, StandardCharsets.UTF_8).trim()
                    var labelStr = ""
                    if (hasLabel == 1 && buf.remaining() >= 4) {
                        val labelLen = buf.int
                        val paddedLen = ((labelLen + 3) / 4) * 4
                        if (paddedLen in 0..buf.remaining()) {
                            val lBytes = ByteArray(paddedLen)
                            buf.get(lBytes)
                            labelStr = String(lBytes, 0, minOf(labelLen, paddedLen), StandardCharsets.UTF_8).trim()
                        }
                    }
                    val missingCount = abs(nMissing).coerceIn(0, 3)
                    val missingList = mutableListOf<String>()
                    repeat(missingCount) {
                        if (buf.remaining() >= 8) {
                            missingList.add(buf.double.toString())
                        }
                    }
                    if (varTypeCode == -1) {
                        // Continuation slot for string variable > 8 chars
                        slotIsStringContinuation.add(true)
                    } else {
                        slotIsStringContinuation.add(false)
                        val decimals = (printFmt and 0xFF).coerceIn(0, 6)
                        val width = ((printFmt ushr 8) and 0xFF).coerceIn(1, 40)
                        val isStr = varTypeCode > 0
                        variables.add(
                            PsppVariable(
                                name = rawName.lowercase().ifBlank { "var${variables.size + 1}" },
                                type = if (isStr) VariableType.STRING else VariableType.NUMERIC,
                                width = width,
                                decimals = if (isStr) 0 else decimals,
                                label = labelStr,
                                missingValues = missingList,
                                measure = if (isStr) MeasureLevel.NOMINAL else MeasureLevel.SCALE
                            )
                        )
                    }
                }
                3 -> {
                    val count = buf.int
                    val map = linkedMapOf<String, String>()
                    repeat(count) {
                        if (buf.remaining() >= 8) {
                            val numVal = buf.double
                            val keyStr = if (numVal == numVal.roundToLong().toDouble()) {
                                numVal.roundToLong().toString()
                            } else {
                                numVal.toString()
                            }
                            val lLen = buf.get().toInt() and 0xFF
                            val totalLen = ((1 + lLen + 7) / 8) * 8
                            val lBytes = ByteArray(totalLen - 1)
                            if (buf.remaining() >= lBytes.size) {
                                buf.get(lBytes)
                                map[keyStr] = String(lBytes, 0, minOf(lLen, lBytes.size), StandardCharsets.UTF_8).trim()
                            }
                        }
                    }
                    pendingLabels.add(map)
                }
                4 -> {
                    val nVars = buf.int
                    val labelMap = pendingLabels.lastOrNull().orEmpty()
                    repeat(nVars) {
                        if (buf.remaining() >= 4) {
                            val varOneBasedIdx = buf.int
                            val vIdx = varOneBasedIdx - 1
                            if (vIdx in variables.indices) {
                                variables[vIdx] = variables[vIdx].copy(
                                    valueLabels = labelMap,
                                    measure = MeasureLevel.NOMINAL
                                )
                            }
                        }
                    }
                }
                6 -> {
                    // Document record
                    val nLines = buf.int
                    val skip = nLines * 80
                    if (skip in 0..buf.remaining()) {
                        buf.position(buf.position() + skip)
                    }
                }
                7 -> {
                    // Extended Info Record (Subtype 13 = Long Variable Names)
                    if (buf.remaining() >= 12) {
                        val subtype = buf.int
                        val elemSize = buf.int
                        val elemCount = buf.int
                        val skip = elemSize * elemCount
                        if (skip in 0..buf.remaining()) {
                            if (subtype == 13 && skip > 0) {
                                val rawBytes = ByteArray(skip)
                                buf.get(rawBytes)
                                val pairsStr = String(rawBytes, StandardCharsets.UTF_8)
                                val longMap = pairsStr.split('\t')
                                    .mapNotNull { entry ->
                                        val eq = entry.indexOf('=')
                                        if (eq > 0) {
                                            entry.substring(0, eq).trim().lowercase() to entry.substring(eq + 1).trim()
                                        } else null
                                    }
                                    .toMap()
                                for (i in variables.indices) {
                                    val mapped = longMap[variables[i].name.lowercase()]
                                    if (!mapped.isNullOrEmpty()) {
                                        variables[i] = variables[i].copy(name = mapped)
                                    }
                                }
                            } else {
                                buf.position(buf.position() + skip)
                            }
                        }
                    }
                }
                999 -> {
                    if (buf.remaining() >= 4) buf.int // filler 0
                    break@loop
                }
                else -> break@loop
            }
        }

        val rows = mutableListOf<List<String>>()
        val maxCases = if (ncasesHeader > 0) ncasesHeader else 10000

        if (compression == 0) {
            while (rows.size < maxCases && buf.remaining() >= variables.size * 8) {
                val row = mutableListOf<String>()
                for (v in variables) {
                    if (v.type == VariableType.STRING) {
                        val sBytes = ByteArray(8)
                        buf.get(sBytes)
                        row.add(String(sBytes, StandardCharsets.UTF_8).trim())
                    } else {
                        val d = buf.double
                        if (d <= SYSMIS_IEEE / 2 || d.isNaN()) {
                            row.add("")
                        } else {
                            row.add(formatCellNumber(d, v.decimals))
                        }
                    }
                }
                rows.add(row)
            }
        } else {
            // Bytecode-compressed .sav stream (codes 0..255 in 8-byte control clusters)
            val opcodes = IntArray(8)
            var opIdx = 8
            var eof = false

            fun nextOpcode(): Int {
                if (opIdx >= 8) {
                    if (buf.remaining() < 8) return 252
                    for (i in 0 until 8) {
                        opcodes[i] = buf.get().toInt() and 0xFF
                    }
                    opIdx = 0
                }
                return opcodes[opIdx++]
            }

            while (!eof && rows.size < maxCases && buf.hasRemaining()) {
                val row = mutableListOf<String>()
                for (v in variables) {
                    var code = nextOpcode()
                    while (code == 0) code = nextOpcode() // ignore padding nop
                    when (code) {
                        252 -> {
                            eof = true
                            break
                        }
                        253 -> {
                            if (v.type == VariableType.STRING) {
                                if (buf.remaining() >= 8) {
                                    val sBytes = ByteArray(8)
                                    buf.get(sBytes)
                                    row.add(String(sBytes, StandardCharsets.UTF_8).trim())
                                } else row.add("")
                            } else {
                                val d = if (buf.remaining() >= 8) buf.double else Double.NaN
                                row.add(if (d.isNaN()) "" else formatCellNumber(d, v.decimals))
                            }
                        }
                        254 -> row.add("") // 8 spaces
                        255 -> row.add("") // SYSMIS
                        else -> {
                            val value = code.toDouble() - bias
                            row.add(formatCellNumber(value, v.decimals))
                        }
                    }
                }
                if (row.size == variables.size) {
                    rows.add(row)
                }
            }
        }

        return PsppDataset(
            name = datasetName.removeSuffix(".sav").removeSuffix(".zsav"),
            variables = variables,
            rows = rows
        )
    }

    /**
     * Generates a column-type preview for CSV or delimited text before importing.
     */
    fun previewDelimitedText(
        fileName: String,
        rawContent: String,
        delimiter: Char = ',',
        hasHeader: Boolean = true
    ): CsvImportPreviewState {
        val parsedRows = parseDelimitedLines(rawContent, delimiter)
        if (parsedRows.isEmpty()) {
            return CsvImportPreviewState(fileName, rawContent, delimiter, hasHeader)
        }
        val colCount = parsedRows.maxOf { it.size }
        val headerRow = if (hasHeader) parsedRows.first() else List(colCount) { "var${it + 1}" }
        val dataRows = if (hasHeader) parsedRows.drop(1) else parsedRows

        val columns = (0 until colCount).map { colIdx ->
            val rawName = headerRow.getOrNull(colIdx)?.trim().orEmpty()
            val sanitizedName = sanitizeVariableName(rawName, colIdx)
            val nonBlankSamples = dataRows.mapNotNull { r ->
                r.getOrNull(colIdx)?.trim()?.takeIf { it.isNotEmpty() }
            }
            val allNumeric = nonBlankSamples.isNotEmpty() && nonBlankSamples.all { it.toDoubleOrNull() != null }
            val distinctCount = nonBlankSamples.toSet().size
            val inferredType = if (allNumeric) VariableType.NUMERIC else VariableType.STRING
            val inferredMeasure = when {
                !allNumeric -> MeasureLevel.NOMINAL
                distinctCount in 1..5 -> MeasureLevel.NOMINAL
                else -> MeasureLevel.SCALE
            }
            CsvColumnPreview(
                index = colIdx,
                name = sanitizedName,
                inferredType = inferredType,
                inferredMeasure = inferredMeasure,
                sampleValues = nonBlankSamples.take(4)
            )
        }

        return CsvImportPreviewState(
            fileName = fileName,
            rawContent = rawContent,
            delimiter = delimiter,
            hasHeader = hasHeader,
            columns = columns,
            totalRows = dataRows.size
        )
    }

    /**
     * Converts a [CsvImportPreviewState] or raw CSV text into a full [PsppDataset].
     */
    fun parseCsvToDataset(
        fileName: String,
        rawContent: String,
        delimiter: Char = ',',
        hasHeader: Boolean = true
    ): PsppDataset {
        val preview = previewDelimitedText(fileName, rawContent, delimiter, hasHeader)
        val parsedRows = parseDelimitedLines(rawContent, delimiter)
        val dataRows = if (hasHeader && parsedRows.isNotEmpty()) parsedRows.drop(1) else parsedRows

        val variables = preview.columns.map { col ->
            val hasDecimals = col.sampleValues.any { it.contains(".") && !it.endsWith(".0") }
            PsppVariable(
                name = col.name,
                type = col.inferredType,
                width = if (col.inferredType == VariableType.STRING) 16 else 8,
                decimals = if (col.inferredType == VariableType.STRING) 0 else if (hasDecimals) 2 else 0,
                label = col.name.replace('_', ' ').replaceFirstChar { it.uppercase() },
                measure = col.inferredMeasure
            )
        }

        val normalizedRows = dataRows.map { r ->
            List(variables.size) { idx -> r.getOrNull(idx)?.trim().orEmpty() }
        }

        return PsppDataset(
            name = fileName.substringBeforeLast('.'),
            variables = variables,
            rows = normalizedRows
        )
    }

    /**
     * Serializes a [PsppDataset] to RFC-4180 CSV format.
     */
    fun exportDatasetToCsv(dataset: PsppDataset): String {
        val sb = StringBuilder()
        sb.appendLine(dataset.variables.joinToString(",") { escapeCsvCell(it.name) })
        dataset.rows.forEach { row ->
            sb.appendLine(
                dataset.variables.indices.joinToString(",") { idx ->
                    escapeCsvCell(row.getOrNull(idx).orEmpty())
                }
            )
        }
        return sb.toString()
    }

    fun parseDelimitedLines(content: String, delimiter: Char): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val lines = content.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        for (line in lines) {
            if (line.isBlank()) continue
            val cells = mutableListOf<String>()
            val current = StringBuilder()
            var inQuotes = false
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    c == '"' -> {
                        if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                            current.append('"')
                            i++
                        } else {
                            inQuotes = !inQuotes
                        }
                    }
                    c == delimiter && !inQuotes -> {
                        cells.add(current.toString().trim())
                        current.clear()
                    }
                    else -> current.append(c)
                }
                i++
            }
            cells.add(current.toString().trim())
            rows.add(cells)
        }
        return rows
    }

    private fun escapeCsvCell(value: String): String {
        return if (value.contains(',') || value.contains('"') || value.contains('\n')) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }

    fun sanitizeVariableName(raw: String, fallbackIndex: Int): String {
        val cleaned = raw.lowercase()
            .replace(Regex("[^a-z0-9_]"), "_")
            .trim('_')
        return when {
            cleaned.isEmpty() -> "var${fallbackIndex + 1}"
            cleaned.first().isDigit() -> "v_$cleaned"
            else -> cleaned.take(32)
        }
    }

    private fun formatCellNumber(value: Double, decimals: Int): String {
        if (decimals == 0 || value == value.roundToLong().toDouble()) {
            return if (decimals == 0) value.roundToLong().toString()
            else String.format(java.util.Locale.US, "%.${decimals}f", value)
        }
        return String.format(java.util.Locale.US, "%.${decimals}f", value)
    }

    fun saveDatasetToSavFile(dataset: PsppDataset, targetFile: File) {
        targetFile.parentFile?.mkdirs()
        targetFile.writeBytes(writeSavBytes(dataset))
    }
}
