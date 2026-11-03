package io.github.goooseby.keye.domain.reading

import io.github.goooseby.keye.data.model.StudyMaterial

/** Original page numbers are persistent identities; hiding pages never renumbers them. */
fun retainedPageIndices(count: Int,removed: Set<Int>): List<Int> = (0 until count).filter {it+1 !in removed}

fun visiblePositionForPage(indices: List<Int>,originalIndex: Int): Int {
    require(indices.isNotEmpty()) {"请至少保留一页"}
    return indices.indexOfFirst {it>=originalIndex}.takeIf {it>=0} ?: indices.lastIndex
}

fun pageRemovalResult(count: Int,removed: Set<Int>,selection: Set<Int>): Set<Int> {
    require(selection.isNotEmpty() && selection.all {it in 1..count}) {"页面选择无效"}
    val result=removed+selection
    require(retainedPageIndices(count,result).isNotEmpty()) {"请至少保留一页"}
    return result
}

/** The input range uses original, concatenated page numbers across documents. */
fun selectExportPages(materials: List<StudyMaterial>,requested: Set<Int>): Map<String,Set<Int>> {
    var offset=0
    return materials.associate {material ->
        val selected=(1..material.pages.size).filter {it+offset in requested && it !in material.excluded}.toSet()
        offset+=material.pages.size
        material.id to selected
    }
}
