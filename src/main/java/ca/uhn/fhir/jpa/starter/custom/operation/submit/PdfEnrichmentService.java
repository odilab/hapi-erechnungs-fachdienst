package ca.uhn.fhir.jpa.starter.custom.operation.submit;

import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.hl7.fhir.r4.model.Binary;
import org.hl7.fhir.r4.model.DocumentReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

@Service
public class PdfEnrichmentService {

    private static final Logger LOGGER = LoggerFactory.getLogger(PdfEnrichmentService.class);

    public Binary enrichPdfWithBarcodeAndAttachment(byte[] pdfData, String token, DocumentReference document) {
        try {
            byte[] enrichedPdfData = addBarcodeToFirstPage(pdfData, token);
            byte[] structuredData = extractStructuredData(document);
            byte[] finalPdf = createPdfA3WithAttachment(enrichedPdfData, structuredData);

            return new Binary()
                    .setContentType("application/pdf")
                    .setData(finalPdf);

        } catch (Exception e) {
            LOGGER.error("Fehler beim Anreichern der PDF: {}", e.getMessage(), e);
            throw new UnprocessableEntityException("Fehler beim Anreichern der PDF: " + e.getMessage());
        }
    }

    private byte[] addBarcodeToFirstPage(byte[] pdfData, String token) throws IOException {
        try (PDDocument originalDoc = PDDocument.load(pdfData); PDDocument newDoc = new PDDocument()) {
            // QR-Code generieren mit hoher Fehlerkorrektur und minimalem Rand
            int qrSize = 200; // Größe des QR-Codes in Pixeln
            BitMatrix bitMatrix = new MultiFormatWriter().encode(
                    token,
                    BarcodeFormat.QR_CODE,
                    qrSize,
                    qrSize,
                    Map.of(
                            EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H,
                            EncodeHintType.MARGIN, 2
                    )
            );
            BufferedImage qrImage = MatrixToImageWriter.toBufferedImage(bitMatrix);
            PDImageXObject qrPdfImage = LosslessFactory.createFromImage(newDoc, qrImage);

            // Erste Seite verarbeiten
            PDFRenderer renderer = new PDFRenderer(originalDoc);
            PDPage originalPage = originalDoc.getPage(0);
            float pageHeight = originalPage.getMediaBox().getHeight();
            float pageWidth = originalPage.getMediaBox().getWidth();

            // Neue Seite mit zusätzlicher Höhe für QR-Code erstellen
            float qrHeight = 100; // Höhe des QR-Code-Bereichs (ca. 3,5 cm)
            PDPage newPage = new PDPage();
            newPage.setMediaBox(new PDRectangle(pageWidth, pageHeight + qrHeight));
            newDoc.addPage(newPage);

            // ContentStream für die neue Seite
            try (PDPageContentStream contentStream = new PDPageContentStream(newDoc, newPage)) {
                // Originalen Inhalt als Bild rendern und kopieren
                BufferedImage pageImage = renderer.renderImageWithDPI(0, 300);
                PDImageXObject pageImageObj = LosslessFactory.createFromImage(newDoc, pageImage);
                contentStream.drawImage(pageImageObj, 0, 0, pageWidth, pageHeight);

                // QR-Code hinzufügen
                float qrWidth = 85;
                float rightMargin = 40;
                float xPosition = pageWidth - qrWidth - rightMargin;
                float yPosition = pageHeight + (qrHeight - qrWidth) / 2;

                // Weißer Hintergrund für den QR-Code-Bereich
                contentStream.setNonStrokingColor(Color.WHITE);
                contentStream.addRect(0, pageHeight, pageWidth, qrHeight);
                contentStream.fill();

                // QR-Code zeichnen
                contentStream.drawImage(qrPdfImage, xPosition, yPosition, qrWidth, qrWidth);

                // Text unter dem QR-Code mit eingebettetem Font
                PDType1Font font = PDType1Font.HELVETICA;
                contentStream.beginText();
                contentStream.setFont(font, 8);
                contentStream.setNonStrokingColor(Color.BLACK);
                contentStream.newLineAtOffset(xPosition, yPosition - 15);
                contentStream.showText("E-Rechnung-Token");
                contentStream.endText();
            }

            // Restliche Seiten kopieren
            for (int i = 1; i < originalDoc.getNumberOfPages(); i++) {
                PDPage page = originalDoc.getPage(i);
                PDPage newPageCopy = new PDPage(page.getMediaBox());
                newDoc.addPage(newPageCopy);

                BufferedImage pageImageCopy = renderer.renderImageWithDPI(i, 300);
                PDImageXObject pageImageObjCopy = LosslessFactory.createFromImage(newDoc, pageImageCopy);
                try (PDPageContentStream contentStreamCopy = new PDPageContentStream(newDoc, newPageCopy)) {
                    contentStreamCopy.drawImage(pageImageObjCopy, 0, 0, page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
                }
            }

            // PDF in ByteArray speichern
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            newDoc.save(baos);
            return baos.toByteArray();
        } catch (Exception e) {
            LOGGER.error("Fehler beim Hinzufügen des QR-Codes: {}", e.getMessage(), e);
            throw new IOException("Fehler beim Hinzufügen des QR-Codes: " + e.getMessage(), e);
        }
    }

    private byte[] extractStructuredData(DocumentReference document) {
        // Extrahiere die Invoice aus dem DocumentReference
        DocumentReference.DocumentReferenceContentComponent invoiceContent = document.getContent().stream()
                .filter(c -> c.getFormat() != null &&
                        c.getFormat().getSystem() != null &&
                        c.getFormat().getSystem().equals("https://gematik.de/fhir/erg/CodeSystem/erg-attachment-format-cs") &&
                        c.getFormat().getCode() != null &&
                        c.getFormat().getCode().equals("rechnungsinhalt"))
                .findFirst()
                .orElseThrow(() -> new UnprocessableEntityException("Keine Invoice in der DocumentReference gefunden zum Extrahieren für PDF-Anhang"));

        // Hole die Daten (sollten bereits Base64-dekodiert sein, wenn sie von DocumentProcessorService kommen)
        byte[] invoiceData = invoiceContent.getAttachment().getData();
        if (invoiceData == null) {
             throw new UnprocessableEntityException("Invoice-Daten im Attachment sind leer für PDF-Anhang");
        }

        // Annahme: Die Daten in DocumentReference.content[].attachment.data für rechnungsinhalt
        // SIND die rohen JSON-Bytes, NICHT mehr Base64-kodiert an dieser Stelle.
        // Die Dekodierung findet in DocumentProcessorService statt.
        // return Base64.getDecoder().decode(invoiceData); // Nicht mehr nötig hier
         return invoiceData; 
    }

    private byte[] createPdfA3WithAttachment(byte[] pdfData, byte[] structuredData) throws IOException {
        try (PDDocument document = PDDocument.load(pdfData)) {
            // PDF/A-3b Konformität setzen
            PDDocumentCatalog catalog = document.getDocumentCatalog();
            PDMetadata metadata = new PDMetadata(document);
            catalog.setMetadata(metadata);

            // XMP Metadaten für PDF/A-3b Konformität
            String xmp = "<?xpacket begin='ï»¿' id='W5M0MpCehiHzreSzNTczkc9d'?>\n" +
                    "<x:xmpmeta xmlns:x='adobe:ns:meta/'>\n" +
                    "<rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'>\n" +
                    "<rdf:Description rdf:about='' xmlns:pdfaid='http://www.aiim.org/pdfa/ns/id/'>\n" +
                    "<pdfaid:part>3</pdfaid:part>\n" +
                    "<pdfaid:conformance>B</pdfaid:conformance>\n" +
                    "</rdf:Description>\n" +
                    "</rdf:RDF>\n" +
                    "</x:xmpmeta>\n" +
                    "<?xpacket end='w'?>";
            metadata.importXMPMetadata(xmp.getBytes());

            // Embedded Files Dictionary erstellen
            PDEmbeddedFilesNameTreeNode efTree = new PDEmbeddedFilesNameTreeNode();

            // File Specification für die strukturierten Daten
            PDComplexFileSpecification fs = new PDComplexFileSpecification();
            fs.setFile("invoice.json");

            // Embedded File erstellen
            PDEmbeddedFile ef = new PDEmbeddedFile(document);
            ef.setSubtype("application/fhir+json");
            ef.setSize(structuredData.length);
            Calendar now = Calendar.getInstance();
            ef.setCreationDate(now);
            ef.setModDate(now);

            // Strukturierte Daten in das Embedded File schreiben
            try (OutputStream os = ef.createOutputStream()) {
                os.write(structuredData);
            }

            // File Specification mit Embedded File verknüpfen
            fs.setEmbeddedFile(ef);

            // Set relationship in the file specification's dictionary
            COSDictionary dict = fs.getCOSObject();
            dict.setName(COSName.getPDFName("AFRelationship"), "Source");

            // Embedded Files Tree mit File Specification verknüpfen
            Map<String, PDComplexFileSpecification> efMap = new HashMap<>();
            efMap.put("invoice.json", fs);
            efTree.setNames(efMap);

            // Names Dictionary erstellen und mit Embedded Files Tree verknüpfen
            PDDocumentNameDictionary names = new PDDocumentNameDictionary(document.getDocumentCatalog());
            names.setEmbeddedFiles(efTree);
            document.getDocumentCatalog().setNames(names);

            // Add AF entry to the document catalog
            COSArray cosArray = new COSArray();
            cosArray.add(fs.getCOSObject());
            document.getDocumentCatalog().getCOSObject().setItem(COSName.getPDFName("AF"), cosArray);

            // PDF in ByteArray speichern
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            document.save(baos);
            return baos.toByteArray();
        } catch (IOException e) {
            LOGGER.error("Fehler beim Erstellen des PDF/A-3: {}", e.getMessage(), e);
            throw new IOException("Fehler beim Erstellen des PDF/A-3: " + e.getMessage(), e);
        }
    }
} 