package com.personal.tpm.ui

import com.personal.tpm.data.Jv

/** Typed fixtures go through the same factories as real lists; no business request uses these records. */
fun previewCard(type: CardType): CardModel {
    val asset = Jv.obj("assetId" to "preview-asset", "assetName" to "示例资产 · CNC加工中心", "assetCode" to "EWPT-01-017-082J", "addressCode" to "T21", "address" to "一厂 · 设备车间", "model" to "CNC-850", "useState" to "IN_USE", "dept" to Jv.obj("deptName" to "生产一部"), "user" to Jv.obj("realName" to "张三"), "assetCategory" to Jv.obj("categoryName" to "加工设备"), "manufacturer" to "设备制造商", "oldAssetCode" to "OLD-021")
    val person = Jv.obj("realName" to "张三", "userName" to "preview-user")
    val dept = Jv.obj("deptName" to "设备维修科")
    val fault = Jv.obj("faultId" to "preview-fault", "faultName" to "示例维修 · 主轴运行异常", "sn" to "FA261021001", "faultState" to "REPAIRED", "asset" to asset, "applyUser" to person, "user" to person, "dept" to dept, "userGroup" to Jv.obj("groupName" to "维修一组"), "faultTime" to "2026-10-21 08:30", "faultDesc" to "主轴运行异常，需要检修")
    val erp = Jv.obj("outboundId" to "preview-erp", "remark" to "示例 ERP 出库 · 设备维修领料", "outNumber" to "OB261021001", "approvalState" to "EXECUTING", "user" to person, "recipientUser" to person, "dept" to dept, "projectCode" to "210034", "projectDesc" to "设备维护项目", "referenceSn" to "FA261021001", "division" to Jv.obj("divisionDesc" to "一厂 CNC 一部"), "applyDate" to "2026-10-21 09:00", "outboundTime" to "2026-10-21 09:10")
    val model = when (type) {
        CardType.Fault -> faultCardModel(Jv(fault))
        CardType.Erp -> erpCardModel(Jv(erp))
        CardType.TodoFault, CardType.TodoErp, CardType.TodoOther -> {
            val data = when (type) { CardType.TodoFault -> fault.put("faultName", "示例维修待办 · 主轴检修"); CardType.TodoErp -> erp.put("remark", "示例 ERP 待办 · 领料审批"); else -> Jv.obj() }
            todoCardModel(Jv(Jv.obj("id" to "preview-todo", "data" to data, "processDefinitionKey" to when (type) { CardType.TodoFault -> "ASSET_FAULT"; CardType.TodoErp -> "ERP_OUTBOUND"; else -> "OTHER" }, "processInstanceName" to "示例其他待办 · 流程确认", "businessKey" to "preview", "name" to "处理确认", "createTime" to "2026-10-21 09:20")))
        }
        CardType.Asset -> assetCardModel(Jv(asset), 0)
        CardType.Stock -> stockCardModel(Jv(Jv.obj("id" to "preview-stock", "materialDesc" to "示例库存 · 接近开关", "materialCode" to "7.4.09.0869", "onhandQty" to 200, "uomDesc" to "件", "erpTpmCompany" to Jv.obj("organizationName" to "一厂库存组织"), "lot" to "261021", "subInv" to "维修仓", "locatorCode" to "H4.08", "locatorDesc" to "备件区", "updateTime" to "2026-10-21 08:00")), 0)
        CardType.Maintenance, CardType.Precision -> {
            val kind = if (type == CardType.Maintenance) PoolKind.Maintenance else PoolKind.Precision
            poolCardModel(kind, Jv(Jv.obj(kind.idKey to "preview-pool", kind.stateKey to "RECEIVED", kind.nameKey to if (type == CardType.Maintenance) "示例保养 · 月度设备保养" else "示例精密度检查 · 主轴精度检查", "sn" to if (type == CardType.Maintenance) "MA261021001" else "PR261021001", "asset" to asset, "applyUser" to person, "user" to person, "dept" to dept, "userGroup" to Jv.obj("groupName" to "保养一组"), "planStartDate" to "2026-10-21 08:00", "planEndDate" to "2026-10-22 17:00")), 0)
        }
    }
    return model.copy(key = "preview-${type.name}", openId = "", cardType = type)
}
