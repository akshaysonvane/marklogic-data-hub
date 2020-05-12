package com.marklogic.hub.central.controllers.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.marklogic.client.DatabaseClient;
import com.marklogic.client.admin.QueryOptionsManager;
import com.marklogic.client.io.Format;
import com.marklogic.client.io.JacksonHandle;
import com.marklogic.hub.DatabaseKind;
import com.marklogic.hub.central.AbstractHubCentralTest;
import com.marklogic.hub.central.controllers.ModelController;
import com.marklogic.hub.test.Customer;
import com.marklogic.hub.test.ReferenceModelProject;
import com.marklogic.mgmt.ManageClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ModelControllerTest extends AbstractHubCentralTest {

    private final static String MODEL_NAME = "Customer";
    private final static String ENTITY_PROPERTY_1 = "someProperty";
    private final static String ENTITY_PROPERTY_2 = "someOtherProperty";
    private final static String DATABASE_PROPERTY_1 = "testRangeIndexForDHFPROD4704";
    private final static String DATABASE_PROPERTY_2 = "testPathIndexForDHFPROD4704";

    @Autowired
    ModelController controller;

    @AfterEach
    void cleanUp() {
        applyDatabasePropertiesForTests(getHubConfig());
    }

    @Test
    void testModelsServicesEndpoints() {
        createModel();
        updateModelInfo();
        updateModelEntityTypes();
    }

    private void createModel() {
        ArrayNode existingEntityTypes = (ArrayNode) controller.getPrimaryEntityTypes().getBody();
        assertEquals(0, existingEntityTypes.size(), "Any existing models should have been deleted when this test started");

        ObjectNode input = objectMapper.createObjectNode();
        input.put("name", MODEL_NAME);
        JsonNode model = controller.createModel(input).getBody();
        assertEquals(MODEL_NAME, model.get("info").get("title").asText());

        // Create a customer in final so we have a way to verify the entity instance count
        new ReferenceModelProject(getHubClient()).createCustomerInstance(new Customer(1, "Jane"));
        ArrayNode entityTypes = (ArrayNode) controller.getPrimaryEntityTypes().getBody();
        assertEquals(1, entityTypes.size(), "A new model should have been created " +
            "and thus there should be one primary entity type");

        JsonNode customerType = entityTypes.get(0);
        assertEquals("Customer", customerType.get("entityName").asText());
        assertEquals(1, customerType.get("entityInstanceCount").asInt(),
            "Should have a count of one because there's one document in the 'Customer' collection");

    }

    private void updateModelInfo() {
        ModelController.UpdateModelInfoInput info = new ModelController.UpdateModelInfoInput();
        info.description = "Updated description";
        controller.updateModelInfo(MODEL_NAME, info);

        assertEquals("Updated description", loadModel(getHubClient().getFinalClient()).get("definitions").get(MODEL_NAME).get("description").asText());
    }

    private void updateModelEntityTypes() {
        // Loading unrelated indexes so that we can check for them after updating entity model
        loadUnrelatedIndexes();

        String entityTypes = "{\"" + MODEL_NAME + "\" : {\n" +
            "      \"required\" : [ ],\n" +
            "      \"pii\" : [ \"" + ENTITY_PROPERTY_1 + "\" ]," +
            "      \"elementRangeIndex\" : [ \"" + ENTITY_PROPERTY_1 + "\" ],\n" +
            "      \"rangeIndex\" : [ \"" + ENTITY_PROPERTY_2 + "\" ]," +
            "      \"properties\" : {\n" +
            "        \"" + ENTITY_PROPERTY_1 + "\" : {\n" +
            "          \"datatype\" : \"string\",\n" +
            "          \"collation\" : \"http://marklogic.com/collation/codepoint\"\n" +
            "        },\n" +
            "         \"" + ENTITY_PROPERTY_2 + "\" : {\n" +
            "          \"datatype\" : \"string\",\n" +
            "          \"collation\" : \"http://marklogic.com/collation/codepoint\"\n" +
            "        }" +
            "      }\n" +
            "    }}";

        try {
            controller.updateModelEntityTypes(objectMapper.readTree(entityTypes), MODEL_NAME);
        }
        catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }

        assertSearchOptionsDeployment();
        assertPIIFilesDeployment();
        assertIndexDeployment();

        assertEquals("string", loadModel(getHubClient().getFinalClient()).get("definitions").get(MODEL_NAME).get("properties").get(ENTITY_PROPERTY_1).get("datatype").asText());
    }

    private JsonNode loadModel(DatabaseClient client) {
        return client.newJSONDocumentManager().read("/entities/" + MODEL_NAME + ".entity.json", new JacksonHandle()).get();
    }

    private void assertSearchOptionsDeployment() {
        DatabaseClient stagingDatabaseClient = getHubClient().getStagingClient();
        DatabaseClient finalDatabaseClient = getHubClient().getFinalClient();
        Map<String, DatabaseClient> clientMap = new HashMap<>();
        clientMap.put("staging", stagingDatabaseClient);
        clientMap.put("final", finalDatabaseClient);
        clientMap.forEach((databaseKind, databaseClient) -> {
            QueryOptionsManager queryOptionsManager = databaseClient.newServerConfigManager().newQueryOptionsManager();
            assertTrue(queryOptionsManager.readOptionsAs("exp-" + databaseKind + "-entity-options", Format.XML, String.class).contains(MODEL_NAME), "Expected " + MODEL_NAME + " to be in options file " + "exp-" + databaseKind + "-entity-options");
            assertTrue(queryOptionsManager.readOptionsAs(databaseKind + "-entity-options", Format.XML, String.class).contains(MODEL_NAME), "Expected " + MODEL_NAME + " to be in options file " + databaseKind + "-entity-options");
        });
    }

    private void assertPIIFilesDeployment() {
        runAsAdmin();
        ManageClient manageClient = getHubClient().getManageClient();

        try {
            String protectedPaths = manageClient.getJson("/manage/v2/protected-paths");
            assertTrue(protectedPaths.contains(ENTITY_PROPERTY_1), "Expected " + ENTITY_PROPERTY_1 + " to be in protected paths: " + protectedPaths);

            JsonNode queryRolesets = objectMapper.readTree(manageClient.getJson("/manage/v2/query-rolesets"));
            assertTrue(queryRolesets.get("query-roleset-default-list").get("list-items").get("list-count").get("value").asInt() >= 1, "Expected at least 1 query roleset (pii-reader) since we are deploying PII files.");
        }
        catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private void assertIndexDeployment() {
        ManageClient manageClient = getHubClient().getManageClient();
        Stream.of(getHubConfig().getDbName(DatabaseKind.STAGING), getHubConfig().getDbName(DatabaseKind.FINAL)).forEach(databaseKind -> {
            String indexConfig = manageClient.getJson("/manage/v2/databases/" + databaseKind + "/properties");
            assertTrue(indexConfig.contains(DATABASE_PROPERTY_1), "Expected " + DATABASE_PROPERTY_1 + " to be in indexConfig: " + indexConfig);
            assertTrue(indexConfig.contains(DATABASE_PROPERTY_2), "Expected " + DATABASE_PROPERTY_2 + " to be in indexConfig: " + indexConfig);
            assertTrue(indexConfig.contains(ENTITY_PROPERTY_1), "Expected " + ENTITY_PROPERTY_1 + " to be in indexConfig: " + indexConfig);
            assertTrue(indexConfig.contains(ENTITY_PROPERTY_2), "Expected " + ENTITY_PROPERTY_2 + " to be in indexConfig: " + indexConfig);
        });
    }

    private void loadUnrelatedIndexes() {
        String indexConfig = "{\n" +
            "   \"lang\":\"zxx\",\n" +
            "   \"path-namespace\":[\n" +
            "      {\n" +
            "         \"prefix\":\"ex\",\n" +
            "         \"namespace-uri\":\"http://example.org\"\n" +
            "      }\n" +
            "   ],\n" +
            "   \"range-element-index\":[\n" +
            "      {\n" +
            "         \"invalid-values\":\"reject\",\n" +
            "         \"localname\":\"" + DATABASE_PROPERTY_1 + "\",\n" +
            "         \"namespace-uri\":null,\n" +
            "         \"range-value-positions\":false,\n" +
            "         \"scalar-type\":\"decimal\"\n" +
            "      }\n" +
            "   ],\n" +
            "   \"range-path-index\":[\n" +
            "      {\n" +
            "         \"scalar-type\":\"string\",\n" +
            "         \"path-expression\":\"//*:instance/" + DATABASE_PROPERTY_2 + "\",\n" +
            "         \"collation\":\"http://marklogic.com/collation/\",\n" +
            "         \"range-value-positions\":false,\n" +
            "         \"invalid-values\":\"reject\"\n" +
            "      }\n" +
            "   ]\n" +
            "}";

        ManageClient manageClient = getHubClient().getManageClient();
        Stream.of(getHubConfig().getDbName(DatabaseKind.STAGING), getHubConfig().getDbName(DatabaseKind.FINAL))
            .forEach(databaseKind -> manageClient.putJson("/manage/v2/databases/" + databaseKind + "/properties", indexConfig));
    }
}
