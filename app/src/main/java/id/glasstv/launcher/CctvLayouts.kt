package id.glasstv.launcher

import android.content.Context

internal data class CctvLayoutOption(
    val id: String,
    val rows: Int,
    val columns: Int,
    val label: String
) {
    val cameraCount: Int get() = rows * columns
}

internal object CctvLayouts {
    val options = listOf(
        CctvLayoutOption("1x1", 1, 1, "1 × 1 — 1 kamera"),
        CctvLayoutOption("1x2", 1, 2, "1 × 2 — 2 kamera berdampingan"),
        CctvLayoutOption("2x1", 2, 1, "2 × 1 — 2 kamera vertikal"),
        CctvLayoutOption("1x3", 1, 3, "1 × 3 — 3 kamera berdampingan"),
        CctvLayoutOption("3x1", 3, 1, "3 × 1 — 3 kamera vertikal"),
        CctvLayoutOption("2x2", 2, 2, "2 × 2 — 4 kamera"),
        CctvLayoutOption("1x4", 1, 4, "1 × 4 — 4 kamera berdampingan")
    )

    fun selected(context: Context): CctvLayoutOption {
        val saved = context.getSharedPreferences("glass_tv", Context.MODE_PRIVATE)
            .getString("cctv_layout", "2x2")
        return options.firstOrNull { it.id == saved } ?: options.first { it.id == "2x2" }
    }

    fun save(context: Context, layout: CctvLayoutOption) {
        context.getSharedPreferences("glass_tv", Context.MODE_PRIVATE)
            .edit().putString("cctv_layout", layout.id).apply()
    }
}
