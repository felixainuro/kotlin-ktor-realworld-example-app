package io.realworld.app.ext

const val MAIL_REGEX = ("^(([\\w-]+\\.)+[\\w-]+|([a-zA-Z]|[\\w-]{2,}))@"
    + "((([0-1]?[0-9]{1,2}|25[0-5]|2[0-4][0-9])\\.([0-1]?"
    + "[0-9]{1,2}|25[0-5]|2[0-4][0-9])\\."
    + "([0-1]?[0-9]{1,2}|25[0-5]|2[0-4][0-9])\\.([0-1]?"
    + "[0-9]{1,2}|25[0-5]|2[0-4][0-9]))|"
    + "([a-zA-Z]+[\\w-]+\\.)+[a-zA-Z]{2,4})$")

fun String.isEmailValid(): Boolean = !this.isNullOrBlank() && Regex(MAIL_REGEX).matches(this)

private val NON_SLUG_CHARS = Regex("[^\\p{L}\\p{N}]+")
// Accents on Latin letters only: stripping marks from other scripts changes the letter
// (Japanese が would become か, Cyrillic й would become и).
private val LATIN_ACCENTS = Regex("(?<=\\p{IsLatin})\\p{M}+")
private const val MAX_SLUG_CODE_POINTS = 200

/**
 * Slug from a title. Letters and digits of any script survive, so non-Latin titles keep a
 * meaningful slug: "Café au Lait!" -> "cafe-au-lait", "Привет мир" -> "привет-мир".
 * Capped at 200 code points (never splitting a character) so numeric suffixes still fit.
 */
fun String.toSlug(): String {
    val folded = java.text.Normalizer.normalize(this, java.text.Normalizer.Form.NFD).replace(LATIN_ACCENTS, "")
    val slug = java.text.Normalizer.normalize(folded, java.text.Normalizer.Form.NFC)
        .lowercase()
        .replace(NON_SLUG_CHARS, "-")
        .trim('-')
    val capped = if (slug.codePointCount(0, slug.length) <= MAX_SLUG_CODE_POINTS) slug
    else slug.substring(0, slug.offsetByCodePoints(0, MAX_SLUG_CODE_POINTS))
    return capped.trimEnd('-').ifEmpty { "article" }
}
