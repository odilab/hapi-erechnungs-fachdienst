package ca.uhn.fhir.jpa.starter.custom.operation.submit;

import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Meta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class TransformedRechnungMetadataHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(TransformedRechnungMetadataHandler.class);

    // Gematik URLs and Systems for Metadata (hierher verschoben)
    private static final String GEMATIK_ERG_SD_MARKIERUNG_URL = "https://gematik.de/fhir/erg/StructureDefinition/erg-documentreference-markierung";
    private static final String GEMATIK_ERG_MARKIERUNG_EXTENSION_NAME = "markierung";
    private static final String GEMATIK_ERG_CS_DOKUMENT_ART_ARCHIVIERUNG_URL = "https://gematik.de/fhir/erg/CodeSystem/erg-dokument-artderarchivierung-cs";
    private static final String GEMATIK_ERG_DOKUMENT_ART_ARCHIVIERUNG_CODE_PERSOENLICH = "persoenlich";
    private static final String GEMATIK_ERG_DOKUMENT_ART_ARCHIVIERUNG_DISPLAY_PERSOENLICH = "Persönliche Ablage";
    private static final String GEMATIK_ERG_SD_DOKUMENTENMETADATEN_PROFILE_URL = "https://gematik.de/fhir/erg/StructureDefinition/erg-dokumentenmetadaten|1.1.0-RC1";
    private static final String GEMATIK_ERG_CS_RECHNUNGSSTATUS_URL = "https://gematik.de/fhir/erg/CodeSystem/erg-rechnungsstatus-cs";
    private static final String GEMATIK_ERG_RECHNUNGSSTATUS_CODE_OFFEN = "offen";
    private static final String GEMATIK_ERG_RECHNUNGSSTATUS_DISPLAY_OFFEN = "Offen";

    public void applyMetadata(Meta meta) {
        if (meta == null) {
            LOGGER.warn("Meta-Objekt ist null. Metadaten können nicht angewendet werden.");
            // Erwägen, hier eine new Meta() zu erzeugen oder eine Exception zu werfen, 
            // aber da Meta vom Aufrufer übergeben wird, sollte dieser die Initialisierung sicherstellen.
            return; 
        }
        // Vorhandene relevante Metadaten entfernen
        meta.getExtension().removeIf(ext -> GEMATIK_ERG_SD_MARKIERUNG_URL.equals(ext.getUrl()));
        meta.getProfile().clear(); // Alle Profile entfernen, um Duplikate zu vermeiden
        meta.getTag().removeIf(tag -> GEMATIK_ERG_CS_RECHNUNGSSTATUS_URL.equals(tag.getSystem()));

        // 1. Markierungserweiterung hinzufügen
        Extension markierungOuterExt = new Extension(GEMATIK_ERG_SD_MARKIERUNG_URL);
        Extension markierungInnerExt = new Extension(GEMATIK_ERG_MARKIERUNG_EXTENSION_NAME);
        Coding markierungCoding = new Coding()
            .setSystem(GEMATIK_ERG_CS_DOKUMENT_ART_ARCHIVIERUNG_URL)
            .setCode(GEMATIK_ERG_DOKUMENT_ART_ARCHIVIERUNG_CODE_PERSOENLICH)
            .setDisplay(GEMATIK_ERG_DOKUMENT_ART_ARCHIVIERUNG_DISPLAY_PERSOENLICH);
        markierungInnerExt.setValue(markierungCoding);
        markierungOuterExt.addExtension(markierungInnerExt);
        meta.addExtension(markierungOuterExt);

        // 2. Profil hinzufügen
        meta.addProfile(GEMATIK_ERG_SD_DOKUMENTENMETADATEN_PROFILE_URL);

        // 3. Rechnungsstatus-Tag hinzufügen
        meta.addTag(
            GEMATIK_ERG_CS_RECHNUNGSSTATUS_URL,
            GEMATIK_ERG_RECHNUNGSSTATUS_CODE_OFFEN,
            GEMATIK_ERG_RECHNUNGSSTATUS_DISPLAY_OFFEN
        );
        LOGGER.info("Gematik-spezifische Metadaten (Markierung, Profil, Status-Tag) für transformierte Rechnung gesetzt/überschrieben.");
    }
} 