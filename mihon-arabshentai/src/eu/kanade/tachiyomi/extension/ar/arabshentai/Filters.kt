package eu.kanade.tachiyomi.extension.ar.arabshentai

import eu.kanade.tachiyomi.source.model.Filter

class FilterCheckbox(name: String, val uriPart: String) : Filter.CheckBox(name)

class GenresFilter(genres: List<Pair<String, String>>) : Filter.Group<FilterCheckbox>(
    "التصنيفات", genres.distinctBy { it.second }.map { FilterCheckbox(it.first, it.second) },
)

class GenresOpFilter : UriPartFilter(
    "شرط التصنيفات",
    arrayOf(
        "أي تصنيف" to "",
        "جميع التصنيفات" to "1",
    ),
)

class GenreTextFilter : Filter.Text("تصنيف إضافي (اسم التصنيف في الموقع)")

class StatusFilter : Filter.Group<FilterCheckbox>(
    "الحالة",
    arrayOf(
        "الكل" to "",
        "مستمر" to "on-going",
        "مكتمل" to "end",
        "ملغي" to "canceled",
        "متوقف مؤقتاً" to "on-hold",
    ).drop(1).map { FilterCheckbox(it.first, it.second) },
)

class SortFilter : UriPartFilter(
    "الترتيب",
    arrayOf(
        "أحدث الفصول" to "new_chapter",
        "أحدث الأعمال" to "new-manga",
        "أبجدي" to "alphabet",
    ),
)

open class UriPartFilter(displayName: String, private val pairs: Array<Pair<String, String>>) :
    Filter.Select<String>(displayName, pairs.map { it.first }.toTypedArray()) {
    fun toUriPart(): String = pairs[state].second
}
