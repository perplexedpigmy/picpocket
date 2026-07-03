package com.docscanner.domain.filter

enum class FilterType(val label: String) {
    NONE("None"),
    GRAYSCALE("Grayscale"),
    CONTRAST("Contrast"),
    BRIGHTEN("Brighten"),
    SHARPEN("Sharpen"),
    BINARIZE("Binarize"),
}
