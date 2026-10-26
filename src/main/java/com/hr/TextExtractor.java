package com.hr;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Extrait le texte brut d'un PDF, d'un document Word ou d'un fichier texte. */
public final class TextExtractor {

    private TextExtractor() {
    }

    public static boolean supports(String fileName) {
        String n = fileName.toLowerCase(Locale.ROOT);
        return n.endsWith(".pdf") || n.endsWith(".docx") || n.endsWith(".doc") || n.endsWith(".txt");
    }

    public static String extract(String fileName, byte[] data) throws IOException {
        String n = fileName.toLowerCase(Locale.ROOT);
        if (n.endsWith(".pdf")) {
            try (PDDocument doc = Loader.loadPDF(data)) {
                return new PDFTextStripper().getText(doc);
            }
        }
        if (n.endsWith(".docx")) {
            try (XWPFWordExtractor extractor = new XWPFWordExtractor(new XWPFDocument(new ByteArrayInputStream(data)))) {
                return extractor.getText();
            }
        }
        if (n.endsWith(".doc")) {
            try (WordExtractor extractor = new WordExtractor(new ByteArrayInputStream(data))) {
                return extractor.getText();
            }
        }
        return new String(data, StandardCharsets.UTF_8);
    }
}
