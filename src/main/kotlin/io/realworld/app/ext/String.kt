package io.realworld.app.ext

const val MAIL_REGEX = ("^(([\\w-]+\\.)+[\\w-]+|([a-zA-Z]|[\\w-]{2,}))@"
    + "((([0-1]?[0-9]{1,2}|25[0-5]|2[0-4][0-9])\\.([0-1]?"
    + "[0-9]{1,2}|25[0-5]|2[0-4][0-9])\\."
    + "([0-1]?[0-9]{1,2}|25[0-5]|2[0-4][0-9])\\.([0-1]?"
    + "[0-9]{1,2}|25[0-5]|2[0-4][0-9]))|"
    + "([a-zA-Z]+[\\w-]+\\.)+[a-zA-Z]{2,4})$")

fun String.isEmailValid(): Boolean = !this.isNullOrBlank() && Regex(MAIL_REGEX).matches(this)

private val NON_SLUG_CHARS = Regex("[^a-z0-9]+")
private val DIACRITICS = Regex("\\p{M}+")

/** URL-safe slug: "Café au Lait!" -> "cafe-au-lait". Capped so suffixes still fit the column. */
fun String.toSlug(): String =
    java.text.Normalizer.normalize(this, java.text.Normalizer.Form.NFD)
        .replace(DIACRITICS, "")
        .lowercase()
        .replace(NON_SLUG_CHARS, "-")
        .trim('-')
        .take(200)
        .trimEnd('-')
        .ifEmpty { "article" }
