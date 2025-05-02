package ca.uhn.fhir.jpa.starter.custom.interceptor;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.ValidationResult;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import org.hl7.fhir.common.hapi.validation.support.*;
import org.hl7.fhir.common.hapi.validation.validator.FhirInstanceValidator;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.BeanCreationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PostConstruct;
import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import org.hl7.fhir.r4.model.*;
import ca.uhn.fhir.validation.ResultSeverityEnum;
import ca.uhn.fhir.validation.SingleValidationMessage;
import java.io.IOException;
import java.util.stream.Collectors;
import java.util.regex.Pattern;
import java.util.List;


@Component
@Interceptor
public class CustomValidator {
    private static final Logger logger = LoggerFactory.getLogger(CustomValidator.class);
    private final FhirValidator validator;
    private final ValidationSupportChain validationSupportChain;
    private final PrePopulatedValidationSupport prePopulatedSupport;
    private final FhirContext ctx;

    public CustomValidator(FhirContext ctx) {
        this.ctx = ctx;
        logger.info("CustomValidator wird initialisiert...");
        try {
            // NPM Package Support erstellen und Packages laden
            NpmPackageValidationSupport npmPackageSupport = new NpmPackageValidationSupport(ctx);
            npmPackageSupport.loadPackageFromClasspath("classpath:package/de.basisprofil.r4-1.5.3.tgz");
            npmPackageSupport.loadPackageFromClasspath("classpath:package/de.ihe-d.terminology-3.0.1.tgz");
            npmPackageSupport.loadPackageFromClasspath("classpath:package/dvmd.kdl.r4-2024.0.0.tgz");

            logger.info("NPM Package Support erstellt und Packages geladen");

            // PrePopulatedValidationSupport für lokale Ressourcen erstellen und im Feld speichern
            this.prePopulatedSupport = new PrePopulatedValidationSupport(ctx);
            
            // Alle lokalen Ressourcen aus dem resources-Verzeichnis laden
            loadAllResources(this.prePopulatedSupport);
            
            // Validation Support Chain erstellen
            this.validationSupportChain = new ValidationSupportChain(
                npmPackageSupport,
                this.prePopulatedSupport,
                new DefaultProfileValidationSupport(ctx),
                new CommonCodeSystemsTerminologyService(ctx),
                new InMemoryTerminologyServerValidationSupport(ctx),
                new SnapshotGeneratingValidationSupport(ctx)
            );
            logger.info("Validation Support Chain erstellt");

            // Validator mit Caching erstellen
            this.validator = ctx.newValidator();
            FhirInstanceValidator instanceValidator = new FhirInstanceValidator(this.validationSupportChain);
            instanceValidator.setNoTerminologyChecks(false);
            instanceValidator.setErrorForUnknownProfiles(true);
            validator.registerValidatorModule(instanceValidator);
            logger.info("Validator erfolgreich konfiguriert");
        } catch (IOException e) {
            logger.error("Fehler beim Laden der FHIR-Packages", e);
            throw new BeanCreationException("Fehler beim Laden der FHIR-Packages", e);
        }
    }

    @PostConstruct
    public void init() {
        logger.info("CustomValidator wurde erfolgreich initialisiert und ist bereit für Validierungen");
        logger.info("Verwendeter FHIR-Kontext: {}", ctx.getVersion().getVersion());
    }

    @Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_CREATED)
    public void validateResourceCreate(IBaseResource resource) {
        logger.error("====== HOOK CALLED: STORAGE_PRECOMMIT_RESOURCE_CREATED for {} ======", resource.fhirType());
        validateAndThrowIfInvalid(resource);
		  //validator.validateWithResult(resource);
    }

    @Hook(Pointcut.STORAGE_PRECOMMIT_RESOURCE_UPDATED)
    public void validateResourceUpdate(IBaseResource resource) {
        validateAndThrowIfInvalid(resource);
    }

    public void validateAndThrowIfInvalid(IBaseResource resource) {
        logger.debug("Validiere Resource vom Typ: {}", resource.getClass().getSimpleName());
        
        ValidationResult validationResult = validator.validateWithResult(resource);
        
        // Nur Nachrichten mit Severity ERROR oder FATAL sammeln
        List<SingleValidationMessage> errors = validationResult.getMessages().stream()
            .filter(m -> m.getSeverity() == ResultSeverityEnum.ERROR || 
                        m.getSeverity() == ResultSeverityEnum.FATAL)
            .collect(Collectors.toList());
            
        // Nur eine Exception werfen, wenn Fehler oder fatale Fehler vorhanden sind
        if (!errors.isEmpty()) {
            String errorMessage = errors.stream()
                .map(single -> single.getLocationString() + ": " + single.getMessage() + " [" + single.getSeverity() + "]")
                .collect(Collectors.joining("\n"));
                
            logger.error("Validierungsfehler gefunden: \n{}", errorMessage);
            
            // Erstelle OperationOutcome nur mit den Fehlern
            OperationOutcome operationOutcome = new OperationOutcome();
            errors.forEach(message -> {
                OperationOutcome.IssueSeverity severity = OperationOutcome.IssueSeverity.NULL;
                if (message.getSeverity() == ResultSeverityEnum.ERROR) {
                    severity = OperationOutcome.IssueSeverity.ERROR;
                } else if (message.getSeverity() == ResultSeverityEnum.FATAL) {
                    severity = OperationOutcome.IssueSeverity.FATAL;
                }
                operationOutcome.addIssue()
                    .setSeverity(severity)
                    .setCode(OperationOutcome.IssueType.INVALID)
                    .setDiagnostics(message.getLocationString() + ": " + message.getMessage());
            });
            
            throw new UnprocessableEntityException("Validierungsfehler: " + errorMessage, operationOutcome);
        }

        // Logge Warnungen und Informationen, wenn vorhanden
        List<SingleValidationMessage> warningsOrInfo = validationResult.getMessages().stream()
            .filter(m -> m.getSeverity() == ResultSeverityEnum.WARNING ||
                        m.getSeverity() == ResultSeverityEnum.INFORMATION)
            .collect(Collectors.toList());
        if (!warningsOrInfo.isEmpty()) {
            String warningMessage = warningsOrInfo.stream()
                .map(single -> single.getLocationString() + ": " + single.getMessage() + " [" + single.getSeverity() + "]")
                .collect(Collectors.joining("\n"));
            logger.warn("Validierungswarnungen/-informationen gefunden:\n{}", warningMessage);
        }
        
        logger.debug("Resource erfolgreich validiert (oder nur Warnungen/Informationen gefunden)");
    }


    public FhirValidator getValidator() {
        logger.debug("Validator wird abgerufen");
        return validator;
    }

    public ValidationSupportChain getValidationSupportChain() {
        logger.debug("Validation Support Chain wird abgerufen");
        return validationSupportChain;
    }

    public PrePopulatedValidationSupport getPrePopulatedSupport() {
        logger.debug("PrePopulatedValidationSupport wird abgerufen");
        return prePopulatedSupport;
    }

    // Hilfsmethode zum Laden von Ressourcen
    private String loadResourceAsString(String path) throws IOException {
        try (var inputStream = getClass().getResourceAsStream(path)) {
            if (inputStream == null) {
                throw new IOException("Resource nicht gefunden: " + path);
            }
            return new String(inputStream.readAllBytes());
        }
    }

    // Hilfsmethode zum Laden aller Ressourcen (StructureDefinitions, ValueSets, CodeSystems)
    private void loadAllResources(PrePopulatedValidationSupport prePopulatedSupport) throws IOException {
        try (var stream = getClass().getResourceAsStream("/gematik-erg-resources(new)")) {
            if (stream == null) {
                logger.warn("Verzeichnis /gematik-erg-resources(new) nicht gefunden");
                return;
            }
            
            var bufferedReader = new java.io.BufferedReader(new java.io.InputStreamReader(stream));
            String fileName;
            while ((fileName = bufferedReader.readLine()) != null) {
                if (fileName.endsWith(".json")) {
                    try {
                        String resourceContent = loadResourceAsString("/gematik-erg-resources(new)/" + fileName);
                        IBaseResource resource = ctx.newJsonParser().parseResource(resourceContent);
                        
                        if (resource instanceof StructureDefinition) {
                            StructureDefinition sd = (StructureDefinition) resource;
                            prePopulatedSupport.addStructureDefinition(sd);
                            logger.info("StructureDefinition '{}' aus Datei '{}' geladen", sd.getUrl(), fileName);
                        } else if (resource instanceof ValueSet) {
                            ValueSet vs = (ValueSet) resource;
                            prePopulatedSupport.addValueSet(vs);
                            logger.info("ValueSet '{}' aus Datei '{}' geladen", vs.getUrl(), fileName);
                        } else if (resource instanceof CodeSystem) {
                            CodeSystem cs = (CodeSystem) resource;
                            prePopulatedSupport.addCodeSystem(cs);
                            logger.info("CodeSystem '{}' aus Datei '{}' geladen", cs.getUrl(), fileName);
                        }
                    } catch (Exception e) {
                        logger.error("Fehler beim Laden der Datei {}: {}", fileName, e.getMessage());
                    }
                }
            }
        }
    }
} 