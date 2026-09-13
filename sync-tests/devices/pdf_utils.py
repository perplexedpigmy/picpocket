import tempfile
from pathlib import Path

from fpdf import FPDF

from .adb import AdbDevice

_BUNDLED_FONT = Path(__file__).resolve().parent / "fonts" / "DejaVuSans.ttf"


def generate_pdf(path: str, pages: int):
    pdf = FPDF(unit="pt", format="A4")
    pdf.add_font("DejaVuSans", "", str(_BUNDLED_FONT))
    for i in range(pages):
        pdf.add_page()
        pdf.set_fill_color(255, 255, 255)
        pdf.rect(0, 0, 595, 842, style="F")
        pdf.set_text_color(0, 0, 0)
        pdf.set_font("DejaVuSans", size=14)
        pdf.text(40, 60, f"Page {i+1} of {pages} - test content")
    pdf.output(path)


def generate_and_push(adb: AdbDevice, filename_stem: str, pages: int = 1) -> str:
    tmp = Path(tempfile.mkstemp(suffix=".pdf")[1])
    try:
        generate_pdf(str(tmp), pages)
        remote = f"/sdcard/Download/{filename_stem}.pdf"
        adb.push(str(tmp), remote)
        return f"{filename_stem}.pdf"
    finally:
        tmp.unlink(missing_ok=True)
