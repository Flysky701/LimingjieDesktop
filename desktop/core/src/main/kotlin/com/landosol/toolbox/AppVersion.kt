package com.landosol.toolbox

/**
 * The build's own version, as every surface should print it.
 *
 * Diagnostic bundles are read long after the build that produced them is gone, so the version has
 * to travel with them: a 2026-09-19 bundle described behaviour that existed in no commit of this
 * repository and could not be placed against any build. The label therefore goes into the log, the
 * bundle README, and every structured state line.
 */
object AppVersion {
    val name: String = BuildConfig.VERSION_NAME
    val code: Int = BuildConfig.VERSION_CODE

    /** The name with the code in parentheses, e.g. `1.2.3 (10203)`. */
    val label: String = appVersionLabel(name, code)

    /** [label] prefixed with 版本, for anything a person reads directly. */
    val display: String = "版本 $label"
}

internal fun appVersionLabel(name: String, code: Int): String = "$name ($code)"
