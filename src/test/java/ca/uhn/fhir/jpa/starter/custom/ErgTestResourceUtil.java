package ca.uhn.fhir.jpa.starter.custom;

import ca.uhn.fhir.context.FhirContext;
import org.hl7.fhir.r4.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

/**
 * Utility-Klasse für die Erstellung von FHIR-Testressourcen gemäß ERG-Profilen
 */
public class ErgTestResourceUtil {

    private static final Logger logger = LoggerFactory.getLogger(ErgTestResourceUtil.class);

    /**
     * Erstellt einen validen ERG-Patienten gemäß dem ERG-Patient-Profil
     */
    public static Patient createTestErgPatient() {
        Patient patient = new Patient();

        //patient.setId("X114428530");
        // Meta mit Profil URL setzen (inkl. Version)
        Meta meta = new Meta();
        meta.addProfile("https://gematik.de/fhir/erg/StructureDefinition/erg-patient|1.1.0-RC1");
        patient.setMeta(meta);

        // Narrative hinzufügen (dom-6 Constraint)
        Narrative narrative = new Narrative();
        narrative.setStatus(Narrative.NarrativeStatus.GENERATED);
        narrative.setDivAsString("<div xmlns=\"http://www.w3.org/1999/xhtml\">Patientendaten</div>");
        patient.setText(narrative);

        // Identifier (KVNR) - required (min=0, but mustSupport=true)
        Identifier kvnr = patient.addIdentifier();
        // Type muss dem Pattern in identifier-kvid-10 entsprechen
        CodeableConcept kvnrType = new CodeableConcept();
        kvnrType.addCoding()
            .setSystem("http://fhir.de/CodeSystem/identifier-type-de-basis")
            .setCode("KVZ10");
        kvnr.setType(kvnrType);
        kvnr.setSystem("http://fhir.de/sid/gkv/kvid-10");
        kvnr.setValue("X114428530"); // Beispiel KVNR

        // Assigner für KVNR (IKNR der Krankenkasse) - mustSupport=true
        Identifier iknr = new Identifier();
        // Type muss dem Pattern in identifier-iknr entsprechen
        CodeableConcept iknrType = new CodeableConcept();
        iknrType.addCoding()
            .setSystem("http://terminology.hl7.org/CodeSystem/v2-0203")
            .setCode("XX");
        iknr.setType(iknrType);
        iknr.setSystem("http://fhir.de/sid/arge-ik/iknr");
        iknr.setValue("109519018"); // Beispiel IKNR
        kvnr.setAssigner(new Reference().setIdentifier(iknr));

        // Name - mustSupport=true
        HumanName name = patient.addName();
        name.setUse(HumanName.NameUse.OFFICIAL);
        name.setText("Juna"); // mustSupport=true
        name.setFamily("Fuchs"); // mustSupport=true
        // mustSupport Extensions für Family Name hinzufügen
        name.getFamilyElement().addExtension("http://fhir.de/StructureDefinition/humanname-namenszusatz", new StringType("geb. Beispiel")); // namenszusatz
        name.getFamilyElement().addExtension("http://hl7.org/fhir/StructureDefinition/humanname-own-name", new StringType("Fuchs")); // nachname
        name.getFamilyElement().addExtension("http://fhir.de/StructureDefinition/humanname-vorsatzwort", new StringType("von")); // vorsatzwort (Korrigierte URL)

        name.addGiven("Juna"); // mustSupport=true
        name.addPrefix("Dr."); // mustSupport=true
        // mustSupport Extension für Prefix hinzufügen
        if (!name.getPrefix().isEmpty()) {
            name.getPrefix().get(0).addExtension("http://hl7.org/fhir/StructureDefinition/iso21090-EN-qualifier", new CodeType("AC")); // prefix-qualifier (Academic)
        }

        // BirthDate - mustSupport=true (korrigierte Initialisierung)
        Calendar cal = Calendar.getInstance();
        cal.set(1970, Calendar.JANUARY, 1);
        patient.setBirthDate(cal.getTime());

        // Address (Strassenanschrift) - mustSupport=true
        Address strassenanschrift = patient.addAddress();
        strassenanschrift.setType(Address.AddressType.BOTH); // mustSupport=true
        strassenanschrift.addLine("Musterstraße 1a Adresszusatz"); // mustSupport=true
        // mustSupport Extensions für Line hinzufügen
        if (!strassenanschrift.getLine().isEmpty()) {
            StringType lineElement = strassenanschrift.getLine().get(0);
            lineElement.addExtension("http://hl7.org/fhir/StructureDefinition/iso21090-ADXP-streetName", new StringType("Musterstraße")); // Strasse
            lineElement.addExtension("http://hl7.org/fhir/StructureDefinition/iso21090-ADXP-houseNumber", new StringType("1a")); // Hausnummer
            lineElement.addExtension("http://hl7.org/fhir/StructureDefinition/iso21090-ADXP-additionalLocator", new StringType("Adresszusatz")); // Adresszusatz
        }

        strassenanschrift.setCity("Musterstadt"); // mustSupport=true
        strassenanschrift.setPostalCode("12345"); // mustSupport=true
        strassenanschrift.setCountry("DE"); // mustSupport=true

        return patient;
    }

    /**
     * Erstellt einen validen ERG-Practitioner gemäß dem ERG-Person-Profil
     */
    public static Practitioner createTestErgPractitioner() {
        Practitioner practitioner = new Practitioner();
        //practitioner.setId("1-HBA-Testkarte-883110000129083");
        practitioner.setMeta(new Meta().addProfile("https://gematik.de/fhir/erg/StructureDefinition/erg-person|1.1.0-RC1"));

        // Narrative hinzufügen (dom-6 Constraint)
        Narrative narrative = new Narrative();
        narrative.setStatus(Narrative.NarrativeStatus.GENERATED);
        narrative.setDivAsString("<div xmlns=\"http://www.w3.org/1999/xhtml\">Practitioner Details</div>");
        practitioner.setText(narrative);

        // Identifier: Telematik-ID und USt-ID
        // Telematik-ID (korrekter Type und System)
        practitioner.addIdentifier()
            .setType(new CodeableConcept().addCoding(
                new Coding("http://terminology.hl7.org/CodeSystem/v2-0203", "PRN", null)))
            .setSystem("https://gematik.de/fhir/sid/telematik-id")
            .setValue("1-HBA-Testkarte-883110000129083");

        // Name
        HumanName name = practitioner.addName();
        name.setUse(HumanName.NameUse.OFFICIAL);
        name.setFamily("MaiÞer");
        // Korrekte Extension für Namenszusatz (Adelstitel)
        name.getFamilyElement().addExtension("http://fhir.de/StructureDefinition/humanname-namenszusatz", new StringType("von"));
        // Korrekte Extension für Vorsatzwort (akademischer Titel)
        name.getFamilyElement().addExtension("http://hl7.org/fhir/StructureDefinition/humanname-own-prefix", new StringType("Prof"));

        name.addGiven("Roland");
        name.addPrefix("Dr. med.");

        // Telecom: Telefonnummer
        practitioner.addTelecom()
            .setSystem(ContactPoint.ContactPointSystem.PHONE)
            .setValue("+49301234567")
            .setUse(ContactPoint.ContactPointUse.WORK);

        // Qualification: Fachrichtung (mit gültigem Code aus dem ValueSet)
        Practitioner.PractitionerQualificationComponent qualification = practitioner.addQualification();
        qualification.getCode().addCoding()
            .setSystem("http://ihe-d.de/CodeSystems/AerztlicheFachrichtungen") // Korrektes System laut VS
            .setCode("ALLG") // Korrekter Code laut VS (Allgemeinmedizin)
            .setDisplay("Allgemeinmedizin"); // Optionaler Display Name

        // Address
        Address address = practitioner.addAddress();
        address.setType(Address.AddressType.BOTH);
        address.addLine("Hauptstraße 10 Hinterhaus");
        address.setCity("Berlin");
        address.setPostalCode("10115");
        address.setCountry("DE");

        return practitioner;
    }

    /**
     * Erstellt eine valide ERG-Institution gemäß dem ERG-Institution-Profil
     */
    public static Organization createTestErgInstitution() {
        Organization institution = new Organization();
        //institution.setId("erg-institution-example");

        // Meta mit Profil URL setzen (inkl. Version)
        Meta meta = new Meta();
        meta.addProfile("https://gematik.de/fhir/erg/StructureDefinition/erg-institution|1.1.0-RC1");
        institution.setMeta(meta);

        // Narrative hinzufügen (optional, aber gut für Lesbarkeit)
        Narrative narrative = new Narrative();
        narrative.setStatus(Narrative.NarrativeStatus.GENERATED);
        narrative.setDivAsString("<div xmlns=\"http://www.w3.org/1999/xhtml\">Institutionsdetails</div>");
        institution.setText(narrative);

        // Identifier Slicing (mustSupport=true für das Slicing)
        // Telematik-ID (min=0, max=1, mustSupport=true)
        Identifier telematikId = institution.addIdentifier();
        telematikId.setType(new CodeableConcept().addCoding(
                new Coding("http://terminology.hl7.org/CodeSystem/v2-0203", "PRN", null))) // Gemäß Pattern
            .setSystem("https://gematik.de/fhir/sid/telematik-id") // mustSupport=true
            .setValue("5-SMC-B-Testkarte-883110000129072"); // mustSupport=true

        // IKNR (min=0, max=1, mustSupport=true)
        Identifier iknr = institution.addIdentifier();
        iknr.setType(new CodeableConcept().addCoding(
                new Coding("http://terminology.hl7.org/CodeSystem/v2-0203", "XX", null))) // Gemäß Pattern
            .setSystem("http://fhir.de/sid/arge-ik/iknr") // mustSupport=true
            .setValue("123456789"); // mustSupport=true

        // BSNR (min=0, max=1, mustSupport=true)
        Identifier bsnr = institution.addIdentifier();
        bsnr.setType(new CodeableConcept().addCoding(
                new Coding("http://terminology.hl7.org/CodeSystem/v2-0203", "BSNR", null))) // Gemäß Pattern
            .setSystem("https://fhir.kbv.de/NamingSystem/KBV_NS_Base_BSNR") 
            .setValue("987654321"); // mustSupport=true

        // KZVAbrechnungsnummer (min=0, max=1, mustSupport=true)
        Identifier kzva = institution.addIdentifier();
        kzva.setType(new CodeableConcept().addCoding(
                new Coding("http://fhir.de/CodeSystem/identifier-type-de-basis", "KZVA", null))) // Gemäß Pattern
            .setSystem("http://fhir.de/sid/kzbv/kzvabrechnungsnummer") 
            .setValue("112233445"); 

        // USt-ID-Nr (min=0, max=1, mustSupport=true)
        Identifier ustId = institution.addIdentifier();
        ustId.setType(new CodeableConcept().addCoding( 
                new Coding("http://terminology.hl7.org/CodeSystem/v2-0203", "TAX", null)
            ))
            .setValue("DE987654321"); // mustSupport=true

        // Type Slicing (mustSupport=true für das Slicing)
        // Fachrichtung (min=0, max=*, mustSupport=true)
        CodeableConcept fachrichtung = institution.addType();
        fachrichtung.addCoding()
            .setSystem("http://ihe-d.de/CodeSystems/AerztlicheFachrichtungen") // System aus Binding ValueSet
            .setCode("ALLG") // mustSupport=true, Code aus Binding ValueSet
            .setDisplay("Allgemeinmedizin"); // mustSupport für system und code

        // Name (min=0, max=1, mustSupport=true)
        institution.setName("Krankenhaus St. KilianTEST-ONLY");

        // Telecom Slicing (mustSupport=true für das Slicing)
        // Telefon (min=0, max=*, mustSupport=true)
        ContactPoint telefon = institution.addTelecom();
        telefon.setSystem(ContactPoint.ContactPointSystem.PHONE) // mustSupport=true
            .setValue("+491234567890"); // mustSupport=true

        // Address Slicing (mustSupport=true für das Slicing)
        // Strassenanschrift (min=0, max=*, mustSupport=true)
        Address strassenanschrift = institution.addAddress();
        strassenanschrift.setType(Address.AddressType.BOTH); // mustSupport=true
        StringType lineElement = strassenanschrift.addLineElement();
        lineElement.setValue("Hauptstraße 123 Adresszusatz"); // mustSupport=true
        // Extensions für Line (mustSupport=true)
        lineElement.addExtension("http://hl7.org/fhir/StructureDefinition/iso21090-ADXP-streetName", new StringType("Hauptstraße"));
        lineElement.addExtension("http://hl7.org/fhir/StructureDefinition/iso21090-ADXP-houseNumber", new StringType("123"));
        lineElement.addExtension("http://hl7.org/fhir/StructureDefinition/iso21090-ADXP-additionalLocator", new StringType("Adresszusatz"));

        strassenanschrift.setCity("Musterstadt"); // mustSupport=true
        strassenanschrift.setPostalCode("98765"); // mustSupport=true
        strassenanschrift.setCountry("DE"); // mustSupport=true

        // Postfach (min=0, max=*, mustSupport=true)
        Address postfach = institution.addAddress();
        postfach.setType(Address.AddressType.POSTAL); // mustSupport=true
        StringType postfachLineElement = postfach.addLineElement();
        postfachLineElement.setValue("Postfach 1234"); // mustSupport=true
        // Extension für Postfach (mustSupport=true)
        postfachLineElement.addExtension("http://hl7.org/fhir/StructureDefinition/iso21090-ADXP-postBox", new StringType("1234"));

        postfach.setCity("Musterstadt"); // mustSupport=true
        postfach.setPostalCode("98760"); // mustSupport=true
        postfach.setCountry("DE"); // mustSupport=true

        return institution;
    }

    /**
     * Erstellt ein minimal valides ChargeItem für Testzwecke.
     */
    public static ChargeItem createMinimalChargeItem(Patient patient) {
        ChargeItem chargeItem = new ChargeItem();
        // Meta mit Profil URL setzen (ERG Rechnungsposition)
        chargeItem.getMeta().addProfile("https://gematik.de/fhir/erg/StructureDefinition/erg-rechnungsposition|1.1.0-RC1");

        // Extension: Rechnungspositionstyp (Pflicht, min=1)
        Extension rpTypeExt = chargeItem.addExtension();
        rpTypeExt.setUrl("https://gematik.de/fhir/erg/StructureDefinition/erg-rechnungsposition-type");
        // Beispiel: Annahme GOÄ-Position (aus ValueSet erg-rechnungsposition-type-vs)
        Coding rpTypeCoding = new Coding()
            .setSystem("https://gematik.de/fhir/erg/CodeSystem/erg-chargeitem-type-CS") // Korrekter System-URI
            .setCode("GOÄ");
        rpTypeExt.setValue(rpTypeCoding);

        chargeItem.setStatus(ChargeItem.ChargeItemStatus.BILLABLE); // Status ist Pflicht

        // Code ist Pflicht (Beispiel GOÄ Ziffer 1)
        CodeableConcept code = new CodeableConcept();
        Coding goaeCoding = code.addCoding(); // Slice 'GOÄ' (oder GOZ etc.)
        goaeCoding.setSystem("http://fhir.de/CodeSystem/bäk/goä") // Korrektes System direkt verwenden
            .setCode("1")
            .setDisplay("Beratung"); // Display ist mustSupport
        chargeItem.setCode(code);

        // Subject ist Pflicht (Referenz auf den Patienten)
        chargeItem.setSubject(new Reference("Patient/" + patient.getIdElement().getIdPart()));

        // Occurrence[x] ist Pflicht (MUSS Typ Period sein)
        Period occurrencePeriod = new Period();
        Date now = new Date();
        occurrencePeriod.setStart(now); // Start ist mustSupport
        occurrencePeriod.setEnd(now); // End ist mustSupport (kann auch gleich Start sein für Einzelleistung)
        chargeItem.setOccurrence(occurrencePeriod);

        // Quantity ist Pflicht (mit Unit, System, Code)
        SimpleQuantity quantity = new SimpleQuantity();
        quantity.setValue(1); // value ist mustSupport
        quantity.setUnit("Stück"); // unit ist mustSupport
        quantity.setSystem("http://unitsofmeasure.org"); // system ist mustSupport (pattern Uri)
        quantity.setCode("{unit}"); // code ist mustSupport (Beispiel UCUM Code für Stück)
        chargeItem.setQuantity(quantity);

        // PriceOverride ist Pflicht (Betrag)
        Money preis = new Money();
        preis.setValue(10.72).setCurrency("EUR");
        chargeItem.setPriceOverride(preis);
        
        // FactorOverride ist Pflicht (Steigerungsfaktor)
        chargeItem.setFactorOverride(2.3);
        
        return chargeItem;
    }

    /**
     * Konvertiert Byte-Array in Base64-String
     */
    public static byte[] encodeToBase64(byte[] data) {
        return Base64.getEncoder().encode(data);
    }

    /**
     * Erstellt eine valide ERG-Invoice gemäß dem ERG-Rechnung-Profil
     */
    public static Invoice createValidErgInvoice(Patient patient, Practitioner practitioner, Organization institution, ChargeItem chargeItem) {
        Invoice invoice = new Invoice();
        
        // Meta mit Profil URL setzen
        Meta meta = new Meta();
        meta.addProfile("https://gematik.de/fhir/erg/StructureDefinition/erg-rechnung|1.1.0-RC1");
        invoice.setMeta(meta);

        // Narrative hinzufügen (dom-6 Constraint)
        Narrative narrative = new Narrative();
        narrative.setStatus(Narrative.NarrativeStatus.GENERATED);
        narrative.setDivAsString("<div xmlns=\"http://www.w3.org/1999/xhtml\">ERG Privatrechnung</div>");
        invoice.setText(narrative);

        // Pflicht-Extensions hinzufügen
        // Behandlungsart (MUSS)
        Extension behandlungsartExt = invoice.addExtension();
        behandlungsartExt.setUrl("https://gematik.de/fhir/erg/StructureDefinition/erg-behandlungsart");
        // Korrektur: Korrektes Coding aus ValueSet erg-rechnung-behandlungsart-vs verwenden
        Coding behandlungsartCoding = new Coding()
            .setSystem("http://terminology.hl7.org/CodeSystem/v3-ActCode") // Korrektes System laut VS
            .setCode("AMB"); // Korrekter Code laut VS
        behandlungsartExt.setValue(behandlungsartCoding); // setValue mit Coding

        // Fachrichtung (MUSS) - Annahme: Coding hier ist korrekt
        Extension fachrichtungExt = invoice.addExtension();
        fachrichtungExt.setUrl("https://gematik.de/fhir/erg/StructureDefinition/erg-fachrichtung");
        Coding fachrichtungCoding = new Coding()
            .setSystem("http://ihe-d.de/CodeSystems/AerztlicheFachrichtungen")
            .setCode("ALLG"); // Allgemeinmedizin - Überprüfen, ob dies im VS für Fachrichtung ist
        fachrichtungExt.setValue(fachrichtungCoding); 


        // Identifier Slicing (Rechnungsnummer - MUSS)
        Identifier rechnungsnummer = invoice.addIdentifier();
        // Korrektur: Type wieder exakt wie im Pattern des Profil-Slices 'Rechnungsnummer' setzen
        CodeableConcept rechnungsnummerType = new CodeableConcept();
        rechnungsnummerType.addCoding(
             new Coding("https://gematik.de/fhir/erg/CodeSystem/erg-rechnung-identifier-type-cs", "invoice", null));
        rechnungsnummer.setType(rechnungsnummerType)
            .setSystem("urn:oid:1.2.276.0.76.4.10") // Beispiel OID für Rechnungsnummern-System
            .setValue("ERG-R-" + new SimpleDateFormat("yyyyMMddHHmmss").format(new Date()));
        
        // Status (MUSS 'issued')
        invoice.setStatus(Invoice.InvoiceStatus.ISSUED);

        // Type Slicing - Nur Rechnungsart (passt zum Slice 'Rechnungsart')
        invoice.getType().addCoding()
            .setSystem("https://gematik.de/fhir/erg/CodeSystem/erg-rechnungsart-cs")
            .setCode("ABSCHLUSS"); // Korrekter Code aus erg-rechnungsart-cs

        // Subject (behandelte Person - MUSS)
        Reference subjectRef = new Reference("Patient/" + patient.getIdElement().getIdPart());
        subjectRef.setDisplay(patient.getNameFirstRep().getText()); // Name hinzufügen
        invoice.setSubject(subjectRef);

        // Recipient (Rechnungsempfänger - MUSS)
        Reference recipientRef = new Reference("Patient/" + patient.getIdElement().getIdPart());
        recipientRef.setDisplay(patient.getNameFirstRep().getText()); // Name hinzufügen
        // KVID des Empfängers hinzufügen (MUSS)
        Identifier recipientKvid = new Identifier();
        recipientKvid.setSystem("http://fhir.de/sid/gkv/kvid-10")
                     .setValue(patient.getIdentifierFirstRep().getValue()); // KVNR vom Patienten holen
        recipientRef.setIdentifier(recipientKvid);
        invoice.setRecipient(recipientRef);

        // Date (Rechnungsdatum - MUSS)
        invoice.setDate(new Date());

        // Participant Slicing (Leistungserbringer - MUSS)
        Invoice.InvoiceParticipantComponent leistungserbringerPart = invoice.addParticipant();
        leistungserbringerPart.getRole().addCoding()
            .setSystem("https://gematik.de/fhir/erg/CodeSystem/erg-participant-role-CS")
            .setCode("leistungserbringer");
        Reference leistungserbringerActorRef = new Reference("Practitioner/" + practitioner.getIdElement().getIdPart());
        leistungserbringerActorRef.setDisplay(practitioner.getNameFirstRep().getText()); // Name hinzufügen
        // Telematik-ID des Practitioners hinzufügen (optional aber gut)
        Identifier practitionerTelematikId = practitioner.getIdentifier().stream()
            .filter(id -> "https://gematik.de/fhir/sid/telematik-id".equals(id.getSystem()))
            .findFirst().orElse(null);
        if(practitionerTelematikId != null) {
            leistungserbringerActorRef.setIdentifier(practitionerTelematikId);
        }
        leistungserbringerPart.setActor(leistungserbringerActorRef);

        // Issuer (Rechnungsersteller - MUSS)
        Reference issuerRef = new Reference("Organization/" + institution.getIdElement().getIdPart());
        issuerRef.setDisplay(institution.getName()); // Name hinzufügen
        // Telematik-ID der Institution hinzufügen (optional aber gut)
         Identifier institutionTelematikId = institution.getIdentifier().stream()
            .filter(id -> "https://gematik.de/fhir/sid/telematik-id".equals(id.getSystem()))
            .findFirst().orElse(null);
        if(institutionTelematikId != null) {
            issuerRef.setIdentifier(institutionTelematikId);
        }
        invoice.setIssuer(issuerRef);

        // LineItem (mind. 0, aber mustSupport=true)
        Invoice.InvoiceLineItemComponent lineItem = invoice.addLineItem();
        lineItem.setSequence(1); // Sequenz ist Pflicht
        // Referenz auf gespeichertes ChargeItem (mustSupport=true)
        lineItem.setChargeItem(new Reference("ChargeItem/" + chargeItem.getIdElement().getIdPart())); 
        
        // PriceComponent Slicing WIEDER AKTIVIERT (Slice: BruttoBetrag, mustSupport=true)
        Invoice.InvoiceLineItemPriceComponentComponent priceComp = lineItem.addPriceComponent(); 
        priceComp.setType(Invoice.InvoicePriceComponentType.BASE);
        Money price = chargeItem.hasPriceOverride() ? chargeItem.getPriceOverride() : new Money().setCurrency("EUR").setValue(10.72);
        priceComp.setAmount(price);

        // TotalNet (MUSS) - Muss jetzt manuell gesetzt werden, da keine LineItem Preise
        Money totalNet = new Money();
        totalNet.setValue(price.getValue()).setCurrency(price.getCurrency());
        invoice.setTotalNet(totalNet);

        // TotalGross (MUSS) - Annahme: Gleich Netto
        Money totalGross = new Money();
        totalGross.setValue(price.getValue()).setCurrency(price.getCurrency()); 
        invoice.setTotalGross(totalGross);

        // PaymentTerms (mustSupport=true)
        invoice.setPaymentTerms("Zahlungsziel: 14 Tage netto");
        // Extension: Zahlungsziel (MUSS)
        Extension zahlungszielExt = new Extension("https://gematik.de/fhir/erg/StructureDefinition/erg-zahlungsziel");
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DATE, 14);
        zahlungszielExt.setValue(new DateType(calendar.getTime()));
        invoice.getPaymentTermsElement().addExtension(zahlungszielExt);

        // totalPriceComponent WIEDER AKTIVIERT (Slice: SummeRechnungspositionen, mustSupport=true)
        Invoice.InvoiceLineItemPriceComponentComponent totalComp = new Invoice.InvoiceLineItemPriceComponentComponent(); 
        totalComp.setType(Invoice.InvoicePriceComponentType.BASE);
        totalComp.setCode(new CodeableConcept().addCoding(
            new Coding("https://gematik.de/fhir/erg/CodeSystem/erg-total-price-component-type-cs", "SummeRechnungspositionen", null)
        ));
        Money totalNetMoney = invoice.getTotalNet(); 
        totalComp.setAmount(totalNetMoney); 
        invoice.addTotalPriceComponent(totalComp);

        // Invoice.note hinzufügen (mustSupport=true)
        invoice.addNote(new Annotation().setText("Hinweis für den Kostenträger."));


        return invoice;
    }

    /**
     * Erstellt eine valide DocumentReference gemäß dem ERG-Dokumentenmetadaten-Profil.
     * Fügt optional Referenzen zu Patient und Anhang in context.related hinzu.
     */
    public static DocumentReference createValidErgDocumentReference(Patient patient, Practitioner practitioner, Organization institution, Invoice invoice, String anhangDocRefId) {
        DocumentReference docRef = new DocumentReference();

        // Meta mit Profil URL setzen
        Meta meta = new Meta();
        meta.addProfile("https://gematik.de/fhir/erg/StructureDefinition/erg-dokumentenmetadaten|1.1.0-RC1");
        docRef.setMeta(meta);

        // meta.tag (erg-rechnungsstatus) - mustSupport=true
        Coding statusTag = new Coding()
            .setSystem("https://gematik.de/fhir/erg/CodeSystem/erg-rechnungsstatus-cs")
            .setCode("offen") // Gültiger Code
            .setDisplay("Offen");
        docRef.getMeta().addTag(statusTag);

        // meta.extension (markierung) - mustSupport=true
        Extension markierungOuterExt = docRef.getMeta().addExtension();
        markierungOuterExt.setUrl("https://gematik.de/fhir/erg/StructureDefinition/erg-documentreference-markierung");
        Extension markierungInnerExt = markierungOuterExt.addExtension();
        markierungInnerExt.setUrl("markierung");
        // Korrektur: Verwende gültigen Code aus CodeSystem/erg-dokument-artderarchivierung-cs.json
        // Mögliche Codes: "epa" oder "persoenlich"
        markierungInnerExt.setValue(new Coding("https://gematik.de/fhir/erg/CodeSystem/erg-dokument-artderarchivierung-cs", "persoenlich", "Persönliche Ablage"));

        // Narrative hinzufügen (dom-6 Constraint)
        Narrative narrative = new Narrative();
        narrative.setStatus(Narrative.NarrativeStatus.GENERATED);
        narrative.setDivAsString("<div xmlns=\"http://www.w3.org/1999/xhtml\">ERG Dokumentenmetadaten</div>");
        docRef.setText(narrative);

        // Status (MUSS 'current')
        docRef.setStatus(Enumerations.DocumentReferenceStatus.CURRENT);

        // Identifier (min=1, mustSupport=true) - Eindeutige ID vom RE-PS
        docRef.addIdentifier()
            .setSystem("urn:oid:1.2.276.0.76.4.10") // Beispiel OID für internes System
            .setValue("DOC-" + new SimpleDateFormat("yyyyMMddHHmmssSSS").format(new Date()));

        // Type Slicing (min=1, mustSupport=true)
        CodeableConcept type = docRef.getType();
        // Slice: Rechnungstyp (KDL AM010106) - mustSupport=true
        type.addCoding()
            .setSystem("http://dvmd.de/fhir/CodeSystem/kdl")
            .setCode("AM010106")
            .setDisplay("Rechnung ambulante/stationäre Behandlung");

        // Subject (Patient - min=1, mustSupport=true)
        Reference subjectRef = new Reference("Patient/" + patient.getIdElement().getIdPart());
        subjectRef.setDisplay(patient.getNameFirstRep().getText());
        docRef.setSubject(subjectRef);

        // Author (min=1, mustSupport=true) - Referenz auf Practitioner oder Organization mit Telematik-ID
        Reference authorRef = new Reference();
        Identifier authorTelematikId = null;
        String authorDisplay = "Unbekannter Autor"; // Defaultwert setzen

        // Annahme: Der Practitioner ist der primäre Autor, falls vorhanden
        if (practitioner != null && practitioner.hasIdElement() && practitioner.hasName()) {
            authorRef.setReference("Practitioner/" + practitioner.getIdElement().getIdPart());
            // Suche nach Telematik-ID im Practitioner
            authorTelematikId = practitioner.getIdentifier().stream()
                .filter(id -> "https://gematik.de/fhir/sid/telematik-id".equals(id.getSystem()))
                .findFirst().orElse(null);
            // Hole Display Name
            if (!practitioner.getNameFirstRep().isEmpty()) {
                authorDisplay = practitioner.getNameFirstRep().getText();
            }
        } else if (institution != null && institution.hasIdElement() && institution.hasName()) { // Fallback zur Institution
            authorRef.setReference("Organization/" + institution.getIdElement().getIdPart());
            // Suche nach Telematik-ID in der Institution
            authorTelematikId = institution.getIdentifier().stream()
                .filter(id -> "https://gematik.de/fhir/sid/telematik-id".equals(id.getSystem()))
                .findFirst().orElse(null);
            // Hole Display Name
            if (institution.getName() != null) {
                authorDisplay = institution.getName();
            }
        }
        // Sicherstellen, dass display nicht leer ist
        if (authorDisplay == null || authorDisplay.trim().isEmpty()) {
            authorDisplay = "Autor ohne Namen";
        }
        authorRef.setDisplay(authorDisplay); // display setzen

        // Telematik-ID als Identifier (Pflichtprofil "identifier-telematik-id")
        if (authorTelematikId == null) {
            // Erzeuge eine Dummy-Telematik-ID, falls keine gefunden wurde (sollte im Test nicht passieren)
            authorTelematikId = new Identifier()
                .setSystem("https://gematik.de/fhir/sid/telematik-id")
                .setValue("MISSING-TELEMATIK-ID-" + System.currentTimeMillis());
        }
        // Identifier muss den korrekten Typ haben (Voraussetzung für Profil http://fhir.de/StructureDefinition/identifier-telematik-id)
        if (!authorTelematikId.hasType() || !authorTelematikId.getType().hasCoding("http://terminology.hl7.org/CodeSystem/v2-0203", "PRN")) {
            authorTelematikId.setType(new CodeableConcept().addCoding(new Coding("http://terminology.hl7.org/CodeSystem/v2-0203", "PRN", null)));
        }
        authorRef.setIdentifier(authorTelematikId); // identifier setzen

        docRef.addAuthor(authorRef);

        // Description (min=1, mustSupport=true)
        docRef.setDescription("E-Rechnung für " + patient.getNameFirstRep().getText() + " vom " + new SimpleDateFormat("dd.MM.yyyy").format(invoice.hasDate() ? invoice.getDate() : new Date())); // Null-Check für Datum

        // Content Slicing (mustSupport=true für Slicing)
        // Constraint RechnungOderAnhang: Entweder 'anhang' ODER ('rechnungspdf' UND 'strukturierterRechnungsinhalt')
        try {
            // Generiere PDF aus der Invoice
            byte[] pdfData = createPdfFromInvoice(invoice);

            // Slice: rechnungspdf (min=0, max=1, mustSupport=true)
            DocumentReference.DocumentReferenceContentComponent pdfContent = docRef.addContent();
            pdfContent.getAttachment()
                .setContentType("application/pdf") // patternCode, mustSupport=true
                .setData(pdfData); // Setze die generierten PDF-Daten
            // .setUrl("Binary/example-pdf"); // Alternative, wenn als Binary gespeichert
            pdfContent.setFormat(new Coding() // format min=1, mustSupport=true
                .setSystem("https://gematik.de/fhir/erg/CodeSystem/erg-attachment-format-cs")
                .setCode("erechnung")); // patternCoding

            // Slice: strukturierterRechnungsinhalt (min=0, max=1, mustSupport=true)
            DocumentReference.DocumentReferenceContentComponent structuredContent = docRef.addContent();
            // Invoice in JSON oder XML umwandeln
            String invoiceString;
            // Entscheide dich für ein Format (z.B. JSON)
            invoiceString = FhirContext.forR4().newJsonParser().setPrettyPrint(true).encodeResourceToString(invoice);
            String contentType = "application/fhir+json";

            structuredContent.getAttachment()
                .setContentType(contentType) // mime-type, mustSupport=true (aus ValueSet)
                .setData(encodeToBase64(invoiceString.getBytes())); // data min=1, mustSupport=true
            // .setUrl("Binary/example-invoice-json"); // Alternative
            structuredContent.setFormat(new Coding() // format min=1, mustSupport=true
                .setSystem("https://gematik.de/fhir/erg/CodeSystem/erg-attachment-format-cs")
                .setCode("rechnungsinhalt")); // patternCoding

        } catch (IOException e) {
            logger.error("Fehler beim Erstellen oder Einbetten der PDF in DocumentReference: {}", e.getMessage(), e);
            // Füge hier ggf. Fehlerbehandlung hinzu, z.B. Dummy-Daten setzen oder Exception werfen
            throw new RuntimeException("Konnte PDF für DocumentReference nicht erstellen", e);
        }
        // Context (mustSupport=true)
        DocumentReference.DocumentReferenceContextComponent context = new DocumentReference.DocumentReferenceContextComponent();
        docRef.setContext(context);

        // Context.related: Referenzen zu Patient und Anhang hinzufügen (jetzt optional laut Profil)
        // Patienten-Referenz
        Reference relatedPatientRef = new Reference("Patient/" + patient.getIdElement().getIdPart());
        relatedPatientRef.setType("Patient");
        relatedPatientRef.setDisplay(patient.getNameFirstRep().getText());
        context.addRelated(relatedPatientRef);

        // Anhangs-Referenz
//        Reference relatedAnhangRef = new Reference("DocumentReference/" + anhangDocRefId);
//        relatedAnhangRef.setType("DocumentReference");
//        relatedAnhangRef.setDisplay("Referenz auf Anhangsdokument (ID: " + anhangDocRefId + ")");
//        context.addRelated(relatedAnhangRef);

        // Extensions (mustSupport=true für die Extensions selbst)
        // Extension: docRef-signature (min=0, max=1, mustSupport=true) - Pflicht wegen Constraint SignaturVerpflichtendRechnung
        Extension signatureExt = docRef.addExtension();
        signatureExt.setUrl("https://gematik.de/fhir/erg/StructureDefinition/erg-docref-signature");
        Signature signature = new Signature();
        // Type ist Pflicht in Signature
        signature.addType().setSystem("urn:iso-astm:E1762-95:2013").setCode("1.2.840.10065.1.12.1.1");
        signature.setWhen(new Date()); // when ist Pflicht
        signature.setWho(authorRef.copy()); // who ist Pflicht (Referenz auf den Autor)
        // data ist optional (der eigentliche Signaturwert)
        // signature.setData(encodeToBase64("DUMMY-SIGNATUR".getBytes()));
        signatureExt.setValue(signature);

        // Extension: rechnungsdatum (min=0, max=1, mustSupport=true)
        if (invoice.hasDate()) { // Prüfen ob Datum vorhanden
            Extension rechnungsdatumExt = docRef.addExtension();
            rechnungsdatumExt.setUrl("https://gematik.de/fhir/erg/StructureDefinition/erg-documentreference-rechnungsdatum");
            // Korrektur: DateTimeType verwenden
            rechnungsdatumExt.setValue(new DateTimeType(invoice.getDate()));
        }

        // Extension: zahlungszieldatum (min=0, max=1, mustSupport=true)
        // Hole Zahlungsziel aus der Invoice Extension (wenn vorhanden)
        List<Extension> zahlungszielExtList = invoice.getPaymentTermsElement().getExtensionsByUrl("https://gematik.de/fhir/erg/StructureDefinition/erg-zahlungsziel");
        if (!zahlungszielExtList.isEmpty()) {
            Extension ext = zahlungszielExtList.get(0);
            if (ext.getValue() instanceof BaseDateTimeType) { // Prüfe auf Basisklasse
                Date dateValue = ((BaseDateTimeType) ext.getValue()).getValue();
                if (dateValue != null) {
                    Extension zahlungszielExtDocRef = docRef.addExtension();
                    zahlungszielExtDocRef.setUrl("https://gematik.de/fhir/erg/StructureDefinition/erg-documentreference-zahlungszieldatum");
                    // Immer als DateTimeType speichern, wie von der Extension gefordert
                    zahlungszielExtDocRef.setValue(new DateTimeType(dateValue));
                }
            }
        }

        // Extension: gesamtbetrag (min=0, max=1, mustSupport=true)
        if (invoice.hasTotalGross()) {
            Extension gesamtbetragExt = docRef.addExtension();
            gesamtbetragExt.setUrl("https://gematik.de/fhir/erg/StructureDefinition/erg-documentreference-gesamtbetrag");
            gesamtbetragExt.setValue(invoice.getTotalGross().copy());
        }

        // Extension: fachrichtung (min=0, max=1, mustSupport=true)
        List<Extension> fachrichtungExtList = invoice.getExtensionsByUrl("https://gematik.de/fhir/erg/StructureDefinition/erg-fachrichtung");
        if (!fachrichtungExtList.isEmpty()) {
            Extension ext = fachrichtungExtList.get(0);
            if (ext.getValue() instanceof Coding) {
                Extension fachrichtungExtDocRef = docRef.addExtension();
                fachrichtungExtDocRef.setUrl("https://gematik.de/fhir/erg/StructureDefinition/erg-docref-fachrichtung");
                fachrichtungExtDocRef.setValue(ext.getValue().copy());
            }
        }

        // Extension: leistungsart (min=0, max=1, mustSupport=true) - CodeSystem/ValueSet unklar, Beispiel
        Extension leistungsartExt = docRef.addExtension();
        leistungsartExt.setUrl("https://gematik.de/fhir/erg/StructureDefinition/erg-docref-leistungsart");
        leistungsartExt.setValue(new Coding("http://beispiel.de/fhir/CodeSystem/leistungsarten", "Privatabrechnung", "Privatabrechnung"));

        return docRef;
    }

    /**
     * Erstellt eine PDF-Darstellung einer Invoice.
     * @param invoice Die Invoice, aus der das PDF erstellt werden soll.
     * @return Ein Byte-Array, das das PDF repräsentiert.
     * @throws IOException Wenn ein Fehler beim Erstellen des PDFs auftritt.
     */
    public static byte[] createPdfFromInvoice(Invoice invoice) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);

            try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                // Schriftart und -größe setzen
                contentStream.setFont(PDType1Font.HELVETICA_BOLD, 12);
                float yPosition = page.getMediaBox().getHeight() - 50;
                float margin = 50;

                // Titel
                contentStream.beginText();
                contentStream.newLineAtOffset(margin, yPosition);
                contentStream.showText("Rechnung");
                contentStream.endText();
                yPosition -= 30;

                // Rechnungsnummer
                contentStream.setFont(PDType1Font.HELVETICA, 10);
                contentStream.beginText();
                contentStream.newLineAtOffset(margin, yPosition);
                contentStream.showText("Rechnungsnummer: " + (invoice.hasIdentifier() ? invoice.getIdentifierFirstRep().getValue() : "N/A"));
                contentStream.endText();
                yPosition -= 20;

                // Datum
                contentStream.beginText();
                contentStream.newLineAtOffset(margin, yPosition);
                contentStream.showText("Datum: " + (invoice.hasDate() ? new SimpleDateFormat("dd.MM.yyyy").format(invoice.getDate()) : "N/A"));
                contentStream.endText();
                yPosition -= 30;

                // Leistungserbringer
                contentStream.setFont(PDType1Font.HELVETICA_BOLD, 10);
                contentStream.beginText();
                contentStream.newLineAtOffset(margin, yPosition);
                contentStream.showText("Leistungserbringer:");
                contentStream.endText();
                yPosition -= 15;

                contentStream.setFont(PDType1Font.HELVETICA, 10);
                contentStream.beginText();
                contentStream.newLineAtOffset(margin, yPosition);
                contentStream.showText(invoice.hasIssuer() ? invoice.getIssuer().getDisplay() : "N/A");
                contentStream.endText();
                yPosition -= 30;

                // Patient
                contentStream.setFont(PDType1Font.HELVETICA_BOLD, 10);
                contentStream.beginText();
                contentStream.newLineAtOffset(margin, yPosition);
                contentStream.showText("Patient:");
                contentStream.endText();
                yPosition -= 15;

                contentStream.setFont(PDType1Font.HELVETICA, 10);
                contentStream.beginText();
                contentStream.newLineAtOffset(margin, yPosition);
                contentStream.showText(invoice.hasRecipient() ? invoice.getRecipient().getDisplay() : "N/A");
                contentStream.endText();
                yPosition -= 30;

                // Beträge
                contentStream.setFont(PDType1Font.HELVETICA_BOLD, 10);
                contentStream.beginText();
                contentStream.newLineAtOffset(margin, yPosition);
                contentStream.showText("Beträge:");
                contentStream.endText();
                yPosition -= 15;

                contentStream.setFont(PDType1Font.HELVETICA, 10);
                contentStream.beginText();
                contentStream.newLineAtOffset(margin, yPosition);
                contentStream.showText(String.format("Nettobetrag: %.2f EUR", invoice.hasTotalNet() ? invoice.getTotalNet().getValue().doubleValue() : 0.0));
                contentStream.endText();
                yPosition -= 15;

                contentStream.beginText();
                contentStream.newLineAtOffset(margin, yPosition);
                contentStream.showText(String.format("Bruttobetrag: %.2f EUR", invoice.hasTotalGross() ? invoice.getTotalGross().getValue().doubleValue() : 0.0));
                contentStream.endText();
                yPosition -= 30;

                // Zahlungsziel
                contentStream.setFont(PDType1Font.HELVETICA, 10);
                contentStream.beginText();
                contentStream.newLineAtOffset(margin, yPosition);
                contentStream.showText(invoice.hasPaymentTerms() ? invoice.getPaymentTerms() : "Kein Zahlungsziel angegeben");
                contentStream.endText();
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            document.save(baos);
            return baos.toByteArray();
        }
    }

    /**
     * Erstellt eine Test-DocumentReference für einen Anhang.
     * @param patient Der Patient, auf den sich der Anhang bezieht.
     * @return DocumentReference mit Anhang.
     */
    public static DocumentReference createTestAnhang(Patient patient) {
        DocumentReference anhang = new DocumentReference();
        anhang.setStatus(Enumerations.DocumentReferenceStatus.CURRENT);

        // Type mit KDL Coding
        CodeableConcept type = new CodeableConcept();
        type.addCoding()
            .setSystem("http://dvmd.de/fhir/CodeSystem/kdl")
            .setCode("PT130102")
            .setDisplay("Molekularpathologiebefund");
        anhang.setType(type);

        // Beschreibung
        anhang.setDescription("Molekularpathologiebefund vom 31.12.21");

        // Subject (Patient)
        if (patient != null && patient.hasIdElement()) {
             anhang.setSubject(new Reference(patient.getIdElement().toUnqualifiedVersionless()));
        } else {
             // Fallback, falls Patient nicht vorhanden oder keine ID hat
             anhang.setSubject(new Reference().setDisplay("Unbekannter Patient"));
        }


        // PDF Content (Dummy)
        DocumentReference.DocumentReferenceContentComponent content = anhang.addContent();
        content.getAttachment()
            .setContentType("application/pdf")
            .setData(encodeToBase64("DIESISTNUREINBEISPIELDIESISTKEINVALIDESPDF00".getBytes()));

        return anhang;
    }
} 