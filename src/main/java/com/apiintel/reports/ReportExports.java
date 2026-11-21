package com.apiintel.reports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Service;

/** CSV and PDF snapshot exports. */
@Service
public class ReportExports {

  public byte[] csv(ObjectNode snapshot) {
    StringBuilder out = new StringBuilder("metric,unit,current,baseline,absoluteChange,relativeChange,status\n");
    for (JsonNode m : snapshot.path("metrics")) {
      out.append(csv(m.path("name").asText())).append(',')
          .append(csv(m.path("unit").asText(""))).append(',')
          .append(m.hasNonNull("current") ? m.path("current").asText() : "").append(',')
          .append(m.hasNonNull("baseline") ? m.path("baseline").asText() : "").append(',')
          .append(m.hasNonNull("absoluteChange") ? m.path("absoluteChange").asText() : "").append(',')
          .append(m.hasNonNull("relativeChange") ? m.path("relativeChange").asText() : "").append(',')
          .append(csv(m.path("status").asText(""))).append('\n');
    }
    return out.toString().getBytes(StandardCharsets.UTF_8);
  }

  private String csv(String v) {
    return '"' + v.replace("\"", "\"\"") + '"';
  }

  /** Minimal PDF: header (windows/freshness/completeness), metric table, tiny series chart, AI summary. */
  public byte[] pdf(ObjectNode snapshot) {
    try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      PDPage page = new PDPage(PDRectangle.LETTER);
      doc.addPage(page);
      PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
      PDType1Font plain = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
      try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
        float y = 720;
        cs.setFont(bold, 16);
        text(cs, 50, y, "Report snapshot " + snapshot.path("id").asText());
        y -= 20;
        cs.setFont(plain, 10);
        JsonNode cur = snapshot.path("resolvedWindows").path("current");
        text(cs, 50, y, "Window: " + cur.path("localStart").asText() + " to "
            + cur.path("localEnd").asText() + " (" + cur.path("zone").asText("") + ")");
        y -= 14;
        text(cs, 50, y, "Freshness: " + snapshot.path("freshness").path("currentLatest").asText("")
            + "   Completeness: " + snapshot.path("completeness").asText("")
            + "   Evaluated: " + snapshot.path("evaluatedAt").asText(""));
        y -= 24;
        cs.setFont(bold, 11);
        text(cs, 50, y, "Metrics");
        y -= 16;
        cs.setFont(plain, 9);
        text(cs, 50, y, pad("metric", 20) + pad("current", 14) + pad("baseline", 14)
            + pad("change", 14) + "status");
        y -= 12;
        for (JsonNode m : snapshot.path("metrics")) {
          if (y < 120) break;
          text(cs, 50, y, pad(m.path("name").asText(), 20)
              + pad(num(m, "current"), 14) + pad(num(m, "baseline"), 14)
              + pad(num(m, "relativeChange"), 14) + m.path("status").asText(""));
          y -= 12;
        }
        // simple line chart of the first timeseries array when present
        y = drawSeries(cs, plain, snapshot, y);
        String summary = snapshot.path("aiSummary").asText("");
        if (!summary.isBlank() && y > 80) {
          cs.setFont(bold, 10);
          text(cs, 50, y, "AI summary (AI-generated)");
          y -= 12;
          cs.setFont(plain, 9);
          for (String line : summary.split("\\n")) {
            if (y < 50) break;
            text(cs, 50, y, line.length() > 110 ? line.substring(0, 110) : line);
            y -= 11;
          }
        }
      }
      doc.save(out);
      return out.toByteArray();
    } catch (Exception e) {
      throw new IllegalStateException("PDF export failed", e);
    }
  }

  private float drawSeries(PDPageContentStream cs, PDType1Font font,
                           ObjectNode snapshot, float y) throws java.io.IOException {
    JsonNode series = snapshot.path("series").path("points");
    if (!series.isArray() || series.size() < 2 || y < 170) return y - 10;
    float x0 = 50, w = 480, h = 90;
    y -= 20;
    cs.setFont(font, 9);
    text(cs, x0, y, "Series");
    float base = y - 12 - h;
    double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
    for (JsonNode p : series) {
      double v = p.path("value").asDouble(0);
      min = Math.min(min, v); max = Math.max(max, v);
    }
    if (max <= min) max = min + 1;
    cs.addRect(x0, base, w, h);
    cs.stroke();
    double prevX = x0, prevY = base + (series.get(0).path("value").asDouble(0) - min) / (max - min) * h;
    for (int i = 1; i < series.size(); i++) {
      double v = series.get(i).path("value").asDouble(0);
      double x = x0 + w * i / (series.size() - 1.0);
      double py = base + (v - min) / (max - min) * h;
      cs.moveTo((float) prevX, (float) prevY);
      cs.lineTo((float) x, (float) py);
      cs.stroke();
      prevX = x; prevY = py;
    }
    return base - 16;
  }

  private void text(PDPageContentStream cs, float x, float y, String s) throws java.io.IOException {
    cs.beginText();
    cs.newLineAtOffset(x, y);
    cs.showText(s == null ? "" : s.replaceAll("[^\\x20-\\x7E]", "?"));
    cs.endText();
  }

  private String num(JsonNode m, String field) {
    return m.hasNonNull(field) ? String.format("%.4g", m.path(field).asDouble()) : "-";
  }

  private String pad(String s, int w) {
    return (s.length() >= w ? s.substring(0, w - 1) + " " : s + " ".repeat(w - s.length()));
  }
}
