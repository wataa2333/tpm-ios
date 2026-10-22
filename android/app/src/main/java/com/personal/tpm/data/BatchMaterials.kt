package com.personal.tpm.data

/** Do not silently invent a quantity when a newly created ERP order returns incomplete/unexpected lines. */
internal fun linkCreatedBatchMaterials(rows: List<Jv>, materials: List<Jv>, quantities: List<Double>): List<Jv> {
    if (materials.isEmpty() || materials.size != quantities.size) throw BatchError("新建出库物料方案不完整")
    val ids = materials.map { it["id"].text }
    if (ids.any { it.isEmpty() } || ids.toSet().size != ids.size || rows.size != materials.size) throw BatchError("新建出库单返回的物料明细不完整")
    val returned = rows.map { it["material"]["id"].text }
    if (returned.toSet() != ids.toSet() || returned.toSet().size != returned.size) throw BatchError("新建出库单返回的物料与申请不一致")
    return rows.map { row ->
        val quantity = quantities[ids.indexOf(row["material"]["id"].text)]
        if (!quantity.isFinite() || quantity < 1) throw BatchError("新建出库物料使用数量无效")
        Jv(Jv.obj("material" to row["material"].raw, "outbound" to row["outbound"].raw, "materialOutbound" to row.raw, "useNum" to quantity))
    }
}

/** Some repair endpoints return the material only inside materialOutbound. Display both shapes. */
fun repairMaterialCode(row: Jv): String = row["material"]["materialCode"].text.ifEmpty {
    row["materialOutbound"]["material"]["materialCode"].text.ifEmpty { row["materialOutbound"]["materialCode"].text }
}
