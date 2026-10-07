package eu.enact.greencharge.sdk;

import eu.enact.dataspaces.Dataspaces;
import eu.enact.dataspaces.config.ConnectorEndpoint;
import eu.enact.dataspaces.config.ConnectorType;
import eu.enact.dataspaces.discovery.ConnectorDiscovery;
import eu.enact.dataspaces.discovery.DiscoveryResult;
import eu.enact.dataspaces.domain.QuerySpec;
import eu.enact.dataspaces.domain.catalog.DataOffer;
import eu.enact.dataspaces.domain.contract.ContractAgreement;
import eu.enact.dataspaces.domain.contract.ContractOffer;
import eu.enact.dataspaces.domain.negotiation.NegotiationStatus;
import eu.enact.dataspaces.domain.transfer.RestApiSink;
import eu.enact.dataspaces.domain.transfer.TransferStatus;
import eu.enact.packaging.api.GeneratedFile;
import eu.enact.packaging.api.OutputFormat;
import eu.enact.packaging.api.PackagingResult;
import eu.enact.packaging.api.PackagingWarning;
import eu.enact.packaging.domain.ContainerPort;
import eu.enact.packaging.domain.DeploymentSpec;
import eu.enact.packaging.domain.ImageSpec;
import eu.enact.packaging.domain.IngressPath;
import eu.enact.packaging.domain.IngressSpec;
import eu.enact.packaging.domain.ProbeSpec;
import eu.enact.packaging.domain.ResourceSpec;
import eu.enact.packaging.domain.ServicePort;
import eu.enact.packaging.domain.ServiceSpec;
import eu.enact.packaging.domain.enums.ImagePullPolicy;
import eu.enact.packaging.domain.enums.ServiceType;
import eu.enact.packaging.generate.DefaultPackagingService;
import eu.enact.packaging.validate.DefaultDeploymentSpecDefaults;
import eu.enact.packaging.validate.DefaultDeploymentSpecValidator;
import eu.enact.validator.model.Diagnostic;
import eu.enact.validator.model.ValidationResponse;
import eu.enact.validator.service.CrdSchemaService;
import eu.enact.validator.service.YamlValidator;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * The ENACT SDK workflow for GreenCharge, run from the command line.
 *
 * <p>The ENACT SDK's Eclipse modules are a UI over the ENACT APM libraries on
 * Maven Central ("the same validated code runs inside and outside Eclipse",
 * SDK README). This tool calls those libraries directly, with the values the
 * challenge brief asks for, so every step is scripted and reproducible:
 *
 * <pre>
 *   package          Application Packaging (app-packaging)    -> Helm chart + manifests
 *   validate-policy  Application Policies (application-policy-model) -> RuntimePolicy checked against the CRD
 *   dataspace        Dataspaces (edc-client)                  -> detect, catalog, negotiate, transfer, download
 * </pre>
 */
public final class SdkWorkflow {

    // Challenge 3 brief, Step 4.
    static final String CONSUMER_MANAGEMENT = "https://sovity2-api.sedimark.work/api/management";
    static final String CONSUMER_DSP = "https://sovity2-api.sedimark.work/api/dsp";
    static final String PROVIDER_DSP = "https://enact-dataspace.iti.gr/edc-provider/api/dsp";
    static final String RELAY = "https://relay.sedimark.work";
    static final String ASSET = "grid-carbon-intensity";

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: SdkWorkflow package <outDir> | validate-policy <file> | dataspace <outDir>");
            System.exit(2);
        }
        switch (args[0]) {
            case "package" -> packageApp(Path.of(args[1]));
            case "validate-policy" -> System.exit(validatePolicy(Path.of(args[1])) ? 0 : 1);
            case "dataspace" -> dataspace(Path.of(args[1]));
            default -> throw new IllegalArgumentException("unknown command " + args[0]);
        }
    }

    // ------------------------------------------------------------------ packaging

    /** HACKATHON.md step 2: image greencharge, port 8080, service, ingress greencharge.local. */
    static DeploymentSpec greenCharge() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("app.kubernetes.io/name", "greencharge");
        labels.put("app.kubernetes.io/part-of", "greencharge-autopilot");
        return DeploymentSpec.builder()
                .name("greencharge")
                .namespace("enact")
                .replicas(1)
                .image(ImageSpec.builder().repository("greencharge").tag("1.5")
                        .pullPolicy(ImagePullPolicy.IF_NOT_PRESENT).build())
                .ports(List.of(ContainerPort.builder().name("http").containerPort(8080)
                        .protocol(ContainerPort.Protocol.TCP).build()))
                .resources(ResourceSpec.builder()
                        .requests(ResourceSpec.ResourceQuantity.builder().cpu("100m").memory("256Mi").build())
                        .limits(ResourceSpec.ResourceQuantity.builder().cpu("1").memory("2Gi").build())
                        .build())
                .service(ServiceSpec.builder().enabled(true).type(ServiceType.NODE_PORT)
                        .ports(List.of(ServicePort.builder().name("http").port(8080).targetPort(8080)
                                .nodePort(32585).protocol(ServicePort.Protocol.TCP).build()))
                        .build())
                .ingress(IngressSpec.builder().enabled(true).hosts(List.of("greencharge.local"))
                        .paths(List.of(IngressPath.builder().path("/").pathType(IngressPath.PathType.PREFIX)
                                .servicePort(8080).build()))
                        .build())
                .readinessProbe(ProbeSpec.builder().port(8080).path("/chargers")
                        .initialDelaySeconds(30).periodSeconds(5).timeoutSeconds(3)
                        .successThreshold(1).failureThreshold(30).build())
                .livenessProbe(ProbeSpec.builder().port(8080).path("/chargers")
                        .initialDelaySeconds(150).periodSeconds(10).timeoutSeconds(3)
                        .successThreshold(1).failureThreshold(3).build())
                .labels(labels)
                .build();
    }

    static void packageApp(Path out) throws Exception {
        var service = new DefaultPackagingService(new DefaultDeploymentSpecDefaults(), new DefaultDeploymentSpecValidator());
        for (OutputFormat format : OutputFormat.values()) {
            PackagingResult result = service.generate(greenCharge(), format);
            // Manifest paths already start with "manifests/"; chart paths start with the chart name.
            Path dir = format == OutputFormat.HELM_CHART ? out.resolve("helm") : out;
            for (GeneratedFile f : result.getFiles()) {
                Path p = dir.resolve(f.getPath());
                Files.createDirectories(p.getParent());
                Files.writeString(p, f.getContent());
                System.out.println("wrote " + out.relativize(p));
            }
            for (PackagingWarning w : result.getWarnings()) {
                System.out.println("warning [" + format + "] " + w.getField() + ": " + w.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ policies

    static boolean validatePolicy(Path file) throws Exception {
        CrdSchemaService schema = new CrdSchemaService();
        schema.init();
        YamlValidator validator = new YamlValidator();
        // The library is written for Spring field injection; set its one dependency.
        Field f = YamlValidator.class.getDeclaredField("schemaService");
        f.setAccessible(true);
        f.set(validator, schema);

        ValidationResponse r = validator.validate(Files.readString(file));
        System.out.println("CRD " + schema.getGroup() + "/" + schema.getVersion() + " " + schema.getKind()
                + ": " + (r.isValid() ? "VALID" : "INVALID"));
        for (Diagnostic d : r.getDiagnostics()) {
            System.out.println("  " + d.getSeverity() + " " + d.getPath() + ": " + d.getMessage());
        }
        return r.isValid();
    }

    // ------------------------------------------------------------------ dataspace

    static void dataspace(Path out) throws Exception {
        Files.createDirectories(out);
        String apiKey = Optional.ofNullable(System.getenv("EDC_API_KEY")).orElse("ApiKeyDefaultValue");
        ConnectorEndpoint endpoint = ConnectorEndpoint.builder()
                .name("siemens-consumer").type(ConnectorType.CONSUMER)
                .managementUrl(CONSUMER_MANAGEMENT).dspUrl(CONSUMER_DSP).apiKeyHeader("X-Api-Key").apiKey(apiKey)
                .build();

        log("detect", "probing " + CONSUMER_MANAGEMENT);
        DiscoveryResult found = ConnectorDiscovery.discover(endpoint);
        log("detect", "reachable=" + found.isReachable() + " authorized=" + found.isAuthorized()
                + " api=" + found.getApiVersion() + " notes=" + found.getNotes());
        endpoint = found.applyTo(endpoint);

        try (Dataspaces ds = Dataspaces.builder().endpoint(endpoint).requestTimeout(Duration.ofSeconds(60)).build()) {
            List<DataOffer> offers = ds.catalog().browse(PROVIDER_DSP);
            DataOffer offer = offers.stream()
                    .filter(o -> o.getContractOffers().stream().anyMatch(c -> ASSET.equals(c.getAssetId())))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(ASSET + " not in the provider catalog"));
            ContractOffer contract = offer.getContractOffers().get(0);
            log("catalog", offers.size() + " offer(s); " + ASSET + " from " + offer.getParticipantId()
                    + ", offer " + contract.getId());

            String negotiationId = ds.negotiations().initiate(contract, PROVIDER_DSP, offer.getParticipantId());
            log("negotiate", "started " + negotiationId);
            String agreementId = null;
            try {
                NegotiationStatus n = ds.negotiations().waitFor(negotiationId, Duration.ofSeconds(3), Duration.ofMinutes(3))
                        .get(4, TimeUnit.MINUTES);
                log("negotiate", "state " + n.state() + (n.errorDetail() == null ? "" : " - " + n.errorDetail()));
                if (n.isFinalized()) {
                    agreementId = n.contractAgreementId();
                }
            } catch (Exception e) {
                log("negotiate", "not finalized within 3 min: " + ds.negotiations().get(negotiationId).state());
            }
            if (agreementId == null) {
                // The provider leaves many negotiations in REQUESTED; use this
                // consumer connector's most recent agreement for the same asset.
                ContractAgreement latest = ds.agreements().list(QuerySpec.builder().limit(500).build()).stream()
                        .filter(a -> ASSET.equals(a.getAssetId()))
                        .max(Comparator.comparing(a -> a.getSigningDate() == null ? Instant.EPOCH : a.getSigningDate()))
                        .orElseThrow(() -> new IllegalStateException("no agreement for " + ASSET));
                agreementId = latest.getId();
                log("negotiate", "using existing agreement " + agreementId + " (signed " + latest.getSigningDate()
                        + ", " + latest.getConsumerId() + " <- " + latest.getProviderId() + ")");
            } else {
                log("negotiate", "agreement " + agreementId);
            }

            String token = HexFormat.of().formatHex(new SecureRandom().generateSeed(16));
            String inbox = RELAY + "/inbox/" + token;
            String transferId = ds.transfers().initiate(agreementId, ASSET, PROVIDER_DSP, RestApiSink.to(inbox));
            log("transfer", "started " + transferId + " -> " + inbox);
            TransferStatus t = ds.transfers().waitFor(transferId, Duration.ofSeconds(3), Duration.ofMinutes(3))
                    .get(4, TimeUnit.MINUTES);
            log("transfer", "state " + t.state() + (t.errorDetail() == null ? "" : " - " + t.errorDetail()));

            HttpResponse<String> r = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(inbox)).timeout(Duration.ofSeconds(60)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            log("download", "relay answered HTTP " + r.statusCode());
            if (r.statusCode() == 200) {
                Path file = out.resolve(ASSET + ".json");
                Files.writeString(file, r.body());
                log("download", "saved " + file.toAbsolutePath() + " (" + r.body().length() + " bytes)");
            } else {
                log("download", "body: " + r.body().strip());
                System.exit(3);
            }
        }
    }

    private static void log(String step, String message) {
        System.out.printf("%s %-9s %s%n", Instant.now().toString().substring(11, 19), step, message);
    }

    private SdkWorkflow() {
    }
}
