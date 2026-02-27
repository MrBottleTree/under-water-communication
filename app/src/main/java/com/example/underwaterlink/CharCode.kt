package com.example.underwaterlink

import android.content.Context

/**
 * Loads the character→code mapping from assets/encoding_table.csv.
 *
 * CSV format (two columns, header row required):
 *   Character,Code
 *   A,10010110
 *   SPACE,0
 *   ...
 *
 * Special names: SPACE → ' ', TAB → '\t', NEWLINE → '\n', CR → '\r'
 * Rows with an empty Code column are silently ignored (character not transmitted).
 *
 * Call CharCode.load(context) once at app start.
 * Then use CharCode.encode / CharCode.decode anywhere.
 */
object CharCode {

    private val SPECIAL = mapOf(
        "SPACE"   to ' ',
        "TAB"     to '\t',
        "NEWLINE" to '\n',
        "CR"      to '\r'
    )

    /** character → bit string, e.g. 'A' → "10010110" */
    var encode: Map<Char, String> = emptyMap()
        private set

    /** bit string → character (reverse of encode) */
    var decode: Map<String, Char> = emptyMap()
        private set

    fun load(context: Context) {
        val encodeMap = mutableMapOf<Char, String>()

        context.assets.open("encoding_table.csv").bufferedReader().useLines { lines ->
            var header = true
            for (line in lines) {
                if (header) { header = false; continue }   // skip header row
                val comma = line.indexOf(',')
                if (comma < 0) continue
                val charCell = line.substring(0, comma).trim()
                val code     = line.substring(comma + 1).trim()
                if (code.isEmpty()) continue                // no code assigned → skip

                val ch = SPECIAL[charCell.uppercase()]
                    ?: if (charCell.length == 1) charCell[0] else continue

                encodeMap[ch] = code
            }
        }

        encode = encodeMap
        decode = encodeMap.entries.associate { (k, v) -> v to k }
    }
}
