package org.seerah.spike.core

import java.io.File

object Fixtures {
    val dist = File(System.getProperty("seerah.packDist"))
    val src = File(System.getProperty("seerah.packSrc"))
    val v1 = File(dist, "pack-1.0.0-test.zip")
    val v2 = File(dist, "pack-1.1.0-test.zip")
    val corrupt = File(dist, "pack-1.1.0-test-corrupt.zip")

    init {
        check(v1.isFile && v2.isFile && corrupt.isFile) { "run spike/pack/build.py first (looked in $dist)" }
    }

    fun content(name: String) = ContentPack.parse(File(src, name).readText().let {
        // src files carry no content_version; the builder injects it.
        it.replaceFirst("{", "{\"content_version\":\"src\",")
    })
}
