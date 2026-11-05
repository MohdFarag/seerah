package org.seerah.spike.core

import java.io.File

object StyleBuilder {
    /** Resolve the package's style template to absolute local paths. No network URLs remain. */
    fun build(packDir: File): String {
        val style = File(packDir, "style.template.json").readText().replace("{PACK}", packDir.absolutePath)
        require(!Regex("\"https?://").containsMatchIn(style)) { "style must not reference the network" }
        return style
    }
}
