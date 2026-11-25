package com.landosol.toolbox.labyrinth.node

import android.content.Context
import android.graphics.BitmapFactory
import com.landosol.toolbox.vision.AndroidPixelImageAdapter
import com.landosol.toolbox.vision.PixelImage

/** Loads node templates from the selected resource pack. */
class AndroidLabyrinthNodeTemplateLoader(
    private val context: Context,
) {
    fun load(): NodeTemplateSet = NodeTemplateSet(
        TEMPLATE_PATHS.mapValues { (_, path) -> loadImage(path) },
    )

    private fun loadImage(path: String): PixelImage {
        val bitmap = context.assets.open(path).use { input ->
            requireNotNull(BitmapFactory.decodeStream(input)) { "Cannot decode node template: $path" }
        }
        return AndroidPixelImageAdapter.from(bitmap).also { bitmap.recycle() }
    }

    internal companion object {
        const val ROOT = "resource-packs/cn-bilibili/vision"
        val TEMPLATE_PATHS = mapOf(
            "node.normal_battle.active" to "$ROOT/node_normal_battle_active.png",
            "node.normal_battle.active.bottom" to "$ROOT/node_normal_battle_active_bottom.png",
            "node.normal_battle.inactive" to "$ROOT/node_normal_battle_inactive.png",
            "node.normal_battle.inactive.bottom" to "$ROOT/node_normal_battle_inactive_bottom.png",
            "node.normal_battle.inactive.video" to "$ROOT/node_normal_battle_inactive_video.png",
            "node.ex_battle.inactive" to "$ROOT/node_ex_battle_inactive.png",
            "node.boss.yellow" to "$ROOT/node_boss_yellow.png",
            "node.boss.dragon" to "$ROOT/node_boss_dragon.png",
            "node.boss.platform" to "$ROOT/node_boss_platform.png",
            "node.clear" to "$ROOT/node_clear.png",
            // Blue cube = 迷宫遗物.
            "node.relic.active" to "$ROOT/node_relic_active.png",
            "node.relic.inactive" to "$ROOT/node_relic_inactive.png",
            "node.relic.map" to "$ROOT/node_relic_map.png",
            "node.event.active" to "$ROOT/node_event_active.png",
            "node.event.active.bottom" to "$ROOT/node_event_active_bottom.png",
            "node.event.inactive" to "$ROOT/node_event_inactive.png",
            "node.event.inactive.bottom" to "$ROOT/node_event_inactive_bottom.png",
            // Pink 印记 statue = 连结.
            "node.link.active" to "$ROOT/node_link_active.png",
            "node.link.active.map" to "$ROOT/node_link_map.png",
            "node.link.inactive" to "$ROOT/node_link_inactive.png",
            "node.shop.active" to "$ROOT/node_shop_active.png",
        )
    }
}
