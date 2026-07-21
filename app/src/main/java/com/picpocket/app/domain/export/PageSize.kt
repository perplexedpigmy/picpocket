package com.picpocket.app.domain.export

enum class PageSize(
    val label: String,
    val shortLabel: String,
    val widthPt: Int,
    val heightPt: Int,
) {
    A0("A0 (841×1189mm)", "A0", 2384, 3370),
    A1("A1 (594×841mm)", "A1", 1684, 2384),
    A2("A2 (420×594mm)", "A2", 1191, 1684),
    A3("A3 (297×420mm)", "A3", 842, 1191),
    A4("A4 (210×297mm)", "A4", 595, 842),
    A5("A5 (148×210mm)", "A5", 420, 595),
    A6("A6 (105×148mm)", "A6", 298, 420),
    LETTER("Letter (8.5×11in)", "Letter", 612, 792),
    LEGAL("Legal (8.5×14in)", "Legal", 612, 1008),
    TABLOID("Tabloid (11×17in)", "Tabloid", 792, 1224),
    ID_CARD("ID card (85.6×54mm)", "ID", 243, 153),
}
