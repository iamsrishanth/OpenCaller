package io.github.iamsrishanth.opencaller.dialer

/** The same keys drive the dial pad and the future contact T9 matcher. */
data class T9Key(val digit: Char, val letters: String)

object T9Keypad {
    val rows: List<List<T9Key>> = listOf(
        listOf(T9Key('1', ""), T9Key('2', "ABC"), T9Key('3', "DEF")),
        listOf(T9Key('4', "GHI"), T9Key('5', "JKL"), T9Key('6', "MNO")),
        listOf(T9Key('7', "PQRS"), T9Key('8', "TUV"), T9Key('9', "WXYZ")),
        listOf(T9Key('*', ""), T9Key('0', "+"), T9Key('#', "")),
    )

    private val digitByLetter: Map<Char, Char> = buildMap {
        rows.flatten().forEach { key ->
            key.letters.forEach { letter -> put(letter, key.digit) }
        }
    }

    /** Converts a contact name or a mixed name/number query to its keypad digits. */
    fun digitsFor(value: String): String = buildString(value.length) {
        value.uppercase().forEach { character ->
            when {
                character.isDigit() -> append(character)
                character in digitByLetter -> append(digitByLetter.getValue(character))
                character == '*' || character == '#' -> append(character)
            }
        }
    }

    fun matches(query: String, contactNameOrNumber: String): Boolean {
        val digits = digitsFor(query)
        return digits.isNotEmpty() && digitsFor(contactNameOrNumber).contains(digits)
    }
}
