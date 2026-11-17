package dev.oddhealth.core

import dev.oddhealth.core.ingest.EvenExport
import java.io.File
import kotlin.test.Test

/** Reads a real export when ODD_EXPORT points at one; skipped otherwise. Prints counts only. */
class RealExportCheck {
    @Test
    fun readRealExport() {
        val path = System.getenv("ODD_EXPORT") ?: return
        val r = EvenExport.read(File(path).inputStream(), 0)
        println("EXPORT " + r.records.groupingBy { it.kind }.eachCount() + " skipped=" + r.skippedRows)
    }
}
