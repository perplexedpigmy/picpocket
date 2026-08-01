import tempfile
from pathlib import Path

from fpdf import FPDF

from .adb import AdbDevice


def generate_pdf(path: str, pages: int):
    pdf = FPDF()
    for i in range(pages):
        pdf.add_page()
        pdf.set_font("Helvetica", size=12)
        pdf.cell(text=f"Page {i+1} of {pages} - test content")
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
