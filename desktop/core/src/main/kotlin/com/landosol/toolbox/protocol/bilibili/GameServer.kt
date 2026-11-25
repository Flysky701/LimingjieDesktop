package com.landosol.toolbox.protocol.bilibili

/**
 * 账号所属服务器。B 服与渠道服是两套独立的游戏服务器；各联运渠道（小米、华为等）
 * 共用同一个渠道服网关，只是客户端包名不同。
 *
 * 渠道服没有可用的 Bilibili SDK 登录：账号页的登录账号 / 密码即 uid / access_key，
 * 直通游戏服登录（与 AutoPCR 的 qsdkclient 一致）。
 *
 * [storageId] 写入账号表的 `serverId` 列，一经发布不可改名。取值与社区流传的
 * 「1.0.9 七渠道可选版」一致，从该版本升级的账号保留所属服务器。
 */
enum class GameServer(
    val storageId: String,
    val displayName: String,
    val packageName: String,
    val isChannelServer: Boolean,
) {
    CN_BILIBILI("cn-bilibili", "国服 Bilibili", "com.bilibili.priconne", isChannelServer = false),
    CN_XIAOMI("cn-xiaomi", "小米渠道服", "com.bilibili.priconne.mi", isChannelServer = true),
    CN_HUAWEI("cn-huawei", "华为渠道服", "com.bilibili.priconne.huawei", isChannelServer = true),
    CN_VIVO("cn-vivo", "vivo 渠道服", "com.bilibili.priconne.vivo", isChannelServer = true),
    CN_OPPO("cn-oppo", "OPPO 渠道服", "com.bilibili.priconne.nearme.gamecenter", isChannelServer = true),
    CN_ALIGAMES("cn-aligames", "九游渠道服", "com.bilibili.priconne.aligames", isChannelServer = true),
    CN_4399("cn-4399", "4399 渠道服", "com.bilibili.priconne.m4399", isChannelServer = true),
    CN_MUMU("cn-mumu", "MuMu 渠道服", "com.bilibili.priconne.yofun.mumu", isChannelServer = true),
    // 应用宝渠道包不沿用 com.bilibili.priconne.* 命名，运营方同为上海幻电。
    CN_TENCENT("cn-tencent", "应用宝渠道服", "com.tencent.tmgp.bilibili.priconne", isChannelServer = true),
    ;

    companion object {
        /**
         * 读不懂的值（更新版本写入、手工改库、旧版本残留）返回 null，由调用方按「服务器未知」处理：
         * 不显示成任何已知服务器、不登录、不自动化，编辑时必须重新选择服务器并重填密码。
         * 绝不兜底成 B 服——那会把「读不懂」变成「悄悄改写成国服」。
         */
        fun fromStorageId(value: String?): GameServer? =
            entries.firstOrNull { it.storageId == value }
    }
}
