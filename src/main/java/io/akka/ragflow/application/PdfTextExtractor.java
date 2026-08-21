package io.akka.ragflow.application;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * Reads a PDF's embedded text layer, page by page (SPEC-001 §4 decision 1). No OCR, no layout or
 * table detection — those are three ONNX models and an XGBoost classifier in the source
 * (question-log research report §2.1), out of scope here. A scanned/image-only page yields empty
 * text, the same as it would from {@code pdfplumber}'s text layer before the source's OCR
 * fallback runs.
 */
public final class PdfTextExtractor {

  private PdfTextExtractor() {}

  public static List<String> extractPages(byte[] pdfBytes) {
    try (PDDocument document = Loader.loadPDF(pdfBytes)) {
      List<String> pages = new ArrayList<>();
      PDFTextStripper stripper = new PDFTextStripper();
      int total = document.getNumberOfPages();
      for (int page = 1; page <= total; page++) {
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        pages.add(stripper.getText(document));
      }
      return pages;
    } catch (IOException e) {
      throw new IllegalArgumentException("could not read PDF: " + e.getMessage(), e);
    }
  }
}
