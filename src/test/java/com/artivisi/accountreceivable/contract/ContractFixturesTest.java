package com.artivisi.accountreceivable.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every example under {@code contracts/v2/examples} is validated against
 * {@code contracts/v2/messages.schema.json}, and the outcome is compared with what
 * {@code manifest.json} says should happen. The contract is published for other teams to build
 * against, so a schema change that silently breaks a published example, or an example that no
 * longer matches the schema, must fail here rather than in someone else's integration.
 *
 * <p>Only schema validity is asserted. A fixture marked {@code schemaValid: true} with a
 * {@code rejection} is one AR refuses on a semantic rule (unknown debtor, plan sum); those rules are
 * exercised by the listener's own tests once it exists.
 */
class ContractFixturesTest {

    private static final Path ROOT = Path.of("contracts", "v2");
    private static final String SCHEMA_ID = "https://artivisi.com/contracts/account-receivable/v2/messages.schema.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TestFactory
    List<DynamicTest> everyFixtureBehavesAsTheManifestSays() throws IOException {
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012,
                builder -> builder.schemaMappers(mappers -> mappers.mapPrefix(
                        "https://artivisi.com/contracts/account-receivable/v2/",
                        ROOT.toAbsolutePath().toUri().toString())));
        SchemaValidatorsConfig config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();

        JsonNode manifest = MAPPER.readTree(ROOT.resolve("examples/manifest.json").toFile());
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode entry : manifest.get("fixtures")) {
            String file = entry.get("file").asText();
            boolean expectValid = entry.get("schemaValid").asBoolean();
            String rejection = entry.hasNonNull("rejection") ? entry.get("rejection").asText() : null;
            JsonSchema schema = factory.getSchema(SchemaLocation.of(SCHEMA_ID + entry.get("schemaRef").asText()), config);

            tests.add(DynamicTest.dynamicTest(file, () -> {
                JsonNode message = MAPPER.readTree(ROOT.resolve(file).toFile());
                Set<ValidationMessage> errors = schema.validate(message);
                String detail = errors.stream().map(ValidationMessage::getMessage).collect(Collectors.joining("; "));
                if (expectValid) {
                    assertThat(errors).as("%s should pass the schema but failed: %s", file, detail).isEmpty();
                } else {
                    assertThat(errors).as("%s is meant to fail the schema (%s) but passed", file, rejection).isNotEmpty();
                }
            }));
        }
        assertThat(tests).as("manifest lists no fixtures").isNotEmpty();
        return tests;
    }

    @TestFactory
    List<DynamicTest> everyExampleFileIsListedInTheManifest() throws IOException {
        JsonNode manifest = MAPPER.readTree(ROOT.resolve("examples/manifest.json").toFile());
        Set<String> listed = new java.util.HashSet<>();
        manifest.get("fixtures").forEach(e -> listed.add(e.get("file").asText()));
        List<DynamicTest> tests = new ArrayList<>();
        try (var files = Files.walk(ROOT.resolve("examples"))) {
            files.filter(p -> p.toString().endsWith(".json") && !p.endsWith("manifest.json")).forEach(p -> {
                String rel = ROOT.relativize(p).toString().replace('\\', '/');
                tests.add(DynamicTest.dynamicTest(rel, () ->
                        assertThat(listed).as("%s exists but is not in manifest.json", rel).contains(rel)));
            });
        }
        return tests;
    }
}
