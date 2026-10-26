package com.hr;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

/** Écrit le classement des CV dans un fichier Excel (.xlsx). */
public final class ExcelReportWriter {

    private static final String[] HEADERS = {
            "Rang", "Score /100", "Candidat", "Email", "Reçu le", "Objet du mail", "Fichier",
            "Synthèse", "Points forts", "Points à vérifier", "Remarque"};
    private static final int[] WIDTHS = {6, 11, 24, 30, 17, 36, 30, 55, 45, 45, 30};

    private ExcelReportWriter() {
    }

    public static void write(Path output, List<EvaluationResult> results, String jobLabel,
                             String modelLabel, boolean keywordMode) throws IOException {
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            CreationHelper helper = wb.getCreationHelper();

            Font white = wb.createFont();
            white.setBold(true);
            white.setColor(IndexedColors.WHITE.getIndex());
            CellStyle header = wb.createCellStyle();
            header.setFont(white);
            header.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setVerticalAlignment(VerticalAlignment.CENTER);
            header.setWrapText(true);

            CellStyle text = wb.createCellStyle();
            text.setVerticalAlignment(VerticalAlignment.TOP);
            text.setWrapText(true);

            CellStyle date = wb.createCellStyle();
            date.setVerticalAlignment(VerticalAlignment.TOP);
            date.setDataFormat(helper.createDataFormat().getFormat("dd/mm/yyyy hh:mm"));

            Font bold = wb.createFont();
            bold.setBold(true);
            CellStyle good = scoreStyle(wb, bold, IndexedColors.LIGHT_GREEN, helper);
            CellStyle medium = scoreStyle(wb, bold, IndexedColors.LIGHT_ORANGE, helper);
            CellStyle bad = scoreStyle(wb, bold, IndexedColors.ROSE, helper);

            // --- Feuille 1 : évaluation ---
            Sheet sheet = wb.createSheet("Évaluation");
            Row headerRow = sheet.createRow(0);
            headerRow.setHeightInPoints(30);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell c = headerRow.createCell(i);
                c.setCellValue(HEADERS[i]);
                c.setCellStyle(header);
                sheet.setColumnWidth(i, WIDTHS[i] * 256);
            }

            int rowNum = 1;
            for (EvaluationResult r : results) {
                CvDocument cv = r.cv();
                Row row = sheet.createRow(rowNum);

                Cell rank = row.createCell(0);
                rank.setCellValue(rowNum);
                rank.setCellStyle(text);

                Cell score = row.createCell(1);
                score.setCellValue(r.score());
                score.setCellStyle(r.score() >= 70 ? good : r.score() >= 40 ? medium : bad);

                put(row, 2, cv.candidateLabel(), text);
                put(row, 3, cv.senderEmail(), text);
                Cell received = row.createCell(4);
                if (cv.receivedAt() != null) {
                    received.setCellValue(cv.receivedAt());
                }
                received.setCellStyle(date);
                put(row, 5, cv.subject(), text);
                put(row, 6, cv.fileName(), text);
                put(row, 7, r.summary(), text);
                put(row, 8, bullets(r.strengths()), text);
                put(row, 9, bullets(r.gaps()), text);
                put(row, 10, remark(r), text);
                rowNum++;
            }
            sheet.createFreezePane(0, 1);
            if (!results.isEmpty()) {
                sheet.setAutoFilter(new CellRangeAddress(0, results.size(), 0, HEADERS.length - 1));
            }

            // --- Feuille 2 : critères utilisés ---
            Sheet criteria = wb.createSheet("Critères");
            criteria.setColumnWidth(0, 34 * 256);
            criteria.setColumnWidth(1, 80 * 256);
            int r = 0;
            r = info(criteria, r, "Descriptif de poste", jobLabel, text, header);
            r = info(criteria, r, "Date d'analyse", LocalDateTime.now().withNano(0).toString().replace('T', ' '), text, header);
            r = info(criteria, r, "CV analysés", String.valueOf(results.size()), text, header);
            r = info(criteria, r, "Évaluation réalisée par", modelLabel, text, header);
            r = info(criteria, r, "Nature du score", keywordMode
                    ? "Part pondérée des mots-clés du descriptif de poste retrouvés dans le CV (comparaison lexicale, "
                    + "sans modèle de langage) : indicative, à confirmer par la lecture du CV"
                    : "Note de 0 à 100 attribuée par un modèle de langage : indicative, à confirmer par la lecture du CV",
                    text, header);
            r = info(criteria, r, "Code couleur du score", "≥ 70 vert · 40–69 orange · < 40 rouge", text, header);

            try (OutputStream out = Files.newOutputStream(output)) {
                wb.write(out);
            }
        }
    }

    private static String bullets(List<String> items) {
        return String.join("\n", items.stream().map(i -> "• " + i).toList());
    }

    private static String remark(EvaluationResult r) {
        if (r.hasError()) {
            return "Évaluation échouée : " + r.error();
        }
        return r.cv().text() == null || r.cv().text().isBlank() ? "Aucun texte extrait (PDF scanné ?)" : "";
    }

    private static CellStyle scoreStyle(XSSFWorkbook wb, Font font, IndexedColors color, CreationHelper helper) {
        CellStyle s = wb.createCellStyle();
        s.setFont(font);
        s.setFillForegroundColor(color.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        s.setAlignment(HorizontalAlignment.CENTER);
        s.setVerticalAlignment(VerticalAlignment.TOP);
        s.setBorderBottom(BorderStyle.THIN);
        s.setDataFormat(helper.createDataFormat().getFormat("0.0"));
        return s;
    }

    private static int info(Sheet sheet, int rowIndex, String label, String value, CellStyle valueStyle, CellStyle labelStyle) {
        Row row = sheet.createRow(rowIndex);
        put(row, 0, label, labelStyle);
        put(row, 1, value, valueStyle);
        return rowIndex + 1;
    }

    private static void put(Row row, int col, String value, CellStyle style) {
        Cell c = row.createCell(col);
        String v = value == null ? "" : value;
        c.setCellValue(v.length() > 32000 ? v.substring(0, 32000) : v);
        c.setCellStyle(style);
    }
}
