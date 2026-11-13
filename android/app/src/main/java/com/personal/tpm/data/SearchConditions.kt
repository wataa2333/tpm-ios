package com.personal.tpm.data

data class SearchCondition(val field: String, val value: String, val exact: Boolean = false) {
    fun toFilter() = filter(field, value, if (exact) "eq" else "like")

    fun matches(text: String): Boolean =
        if (exact) text.equals(value, ignoreCase = true) else text.contains(value, ignoreCase = true)
}
