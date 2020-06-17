package com.marklogic.hub.central.controllers.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.marklogic.client.DatabaseClient;
import com.marklogic.client.FailedRequestException;
import com.marklogic.client.admin.QueryOptionsManager;
import com.marklogic.client.document.GenericDocumentManager;
import com.marklogic.client.io.Format;
import com.marklogic.client.io.JacksonHandle;
import com.marklogic.hub.DatabaseKind;
import com.marklogic.hub.central.AbstractHubCentralTest;
import com.marklogic.hub.central.controllers.ModelController;
import com.marklogic.hub.impl.EntityManagerImpl;
import com.marklogic.hub.test.Customer;
import com.marklogic.hub.test.ReferenceModelProject;
import com.marklogic.mgmt.ManageClient;
import com.marklogic.mgmt.api.API;
import com.marklogic.mgmt.api.database.Database;
import com.marklogic.mgmt.api.database.ElementIndex;
import com.marklogic.mgmt.api.database.PathIndex;
import com.marklogic.mgmt.api.database.PathNamespace;
import com.marklogic.mgmt.mapper.DefaultResourceMapper;
import com.marklogic.mgmt.resource.databases.DatabaseManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

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
        if (isVersionCompatibleWith520Roles()) {
            applyDatabasePropertiesForTests(getHubConfig());
        }
    }

    @Test
    @WithMockUser(roles = {"writeEntityModel"})
    void testModelsServicesEndpoints() {
        createModel();
        updateModelInfo();
        updateModelEntityTypes();
    }

    @Test
    @WithMockUser(roles = {"writeEntityModel"})
    void testModelDeletionEndpoints() {
        installProjectInFolder("test-projects/delete-model-project");
        new EntityManagerImpl(getHubConfig()).deployQueryOptions();

        verifyEntity2BasedArtifactsExist();
        getModelUsage();
        deleteModel();
        verifyEntity2BasedArtifactsDontExist();
        verifyReferencesToEntity2DontExistInEntity1();
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
        Assumptions.assumeTrue(isVersionCompatibleWith520Roles());

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

        assertSearchOptions(MODEL_NAME, Assertions::assertTrue, true);
        assertPIIFilesDeployment();
        assertIndexDeployment();

        assertEquals("string", loadModel(getHubClient().getFinalClient()).get("definitions").get(MODEL_NAME).get("properties").get(ENTITY_PROPERTY_1).get("datatype").asText());
    }

    private void getModelUsage() {
        JsonNode jsonNode = controller.getModelUsage("Entity2").getBody();
        assertNotNull(jsonNode);
        assertEquals(2, jsonNode.get("steps").size());
        assertEquals(1, jsonNode.get("models").size());
        Stream.of("testMap2", "matching-step")
                .forEach(s -> assertTrue(jsonNode.get("steps").toString().contains(s)));
        assertTrue(jsonNode.get("models").toString().contains("Entity1"));
    }

    private void deleteModel() {
        assertThrows(FailedRequestException.class, () -> controller.deleteModel("Entity2"), "Should throw an exception since the entity is referenced in steps.");
        removeReferencesToEntity();
        assertTrue(controller.deleteModel("Entity2").getStatusCode().is2xxSuccessful(), "Should be ok since we deleted the steps that refer to the entity.");
    }

    private JsonNode loadModel(DatabaseClient client) {
        return client.newJSONDocumentManager().read("/entities/" + MODEL_NAME + ".entity.json", new JacksonHandle()).get();
    }

    private void removeReferencesToEntity() {
        DatabaseClient stagingDatabaseClient = getHubClient().getStagingClient();
        DatabaseClient finalDatabaseClient = getHubClient().getFinalClient();
        Stream.of(stagingDatabaseClient, finalDatabaseClient)
                .forEach(databaseClient -> databaseClient
                        .newDocumentManager()
                        .delete("/flows/testFlow.flow.json", "/steps/mapping/testMap2.step.json"));
    }

    private void verifyReferencesToEntity2DontExistInEntity1() {
        assertFalse(getFinalDoc("/entities/Entity1.entity.json").toString().contains("Entity2"), "Expected the properties that refer to Entity2 to be deleted from Entity1.");
        assertFalse(getStagingDoc("/entities/Entity1.entity.json").toString().contains("Entity2"), "Expected the properties that refer to Entity2 to be deleted from Entity1.");
    }

    private void verifyEntity2BasedArtifactsDontExist() {
        assertSearchOptions("Entity2", Assertions::assertFalse, false);
        assertSchemasAndTDE(Assertions::assertNull);
    }

    private void verifyEntity2BasedArtifactsExist() {
        assertSearchOptions("Entity2", Assertions::assertTrue, true);
        assertSchemasAndTDE(Assertions::assertNotNull);
    }

    private void assertSchemasAndTDE(Consumer<Object> assertion) {
        DatabaseClient stagingSchemaClient = getHubConfig().newStagingClient(getHubConfig().getDbName(DatabaseKind.STAGING_SCHEMAS));
        DatabaseClient finalSchemaClient = getHubConfig().newFinalClient(getHubConfig().getDbName(DatabaseKind.FINAL_SCHEMAS));
        Stream.of(stagingSchemaClient, finalSchemaClient).forEach(databaseClient -> {
            GenericDocumentManager documentManager = databaseClient.newDocumentManager();
            assertion.accept(documentManager.exists("/entities/Entity2.entity.schema.json"));
            assertion.accept(documentManager.exists("/entities/Entity2.entity.xsd"));
            assertion.accept(documentManager.exists("/tde/Entity2-1.0.0.tdex"));
        });
    }

    private void assertSearchOptions(String modelName, BiConsumer<Boolean, String> assertion, boolean expected) {
        String startsWith = (expected) ? "Expected " : "Did not expect ";
        DatabaseClient stagingDatabaseClient = getHubClient().getStagingClient();
        DatabaseClient finalDatabaseClient = getHubClient().getFinalClient();
        Map<String, DatabaseClient> clientMap = new HashMap<>();
        clientMap.put("staging", stagingDatabaseClient);
        clientMap.put("final", finalDatabaseClient);
        clientMap.forEach((databaseKind, databaseClient) -> {
            QueryOptionsManager queryOptionsManager = databaseClient.newServerConfigManager().newQueryOptionsManager();
            assertion.accept(queryOptionsManager.readOptionsAs("exp-" + databaseKind + "-entity-options", Format.XML, String.class).contains(modelName), startsWith + modelName + " to be in options file " + "exp-" + databaseKind + "-entity-options");
            assertion.accept(queryOptionsManager.readOptionsAs(databaseKind + "-entity-options", Format.XML, String.class).contains(modelName), startsWith + modelName + " to be in options file " + databaseKind + "-entity-options");
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
        Stream.of(getHubConfig().getDbName(DatabaseKind.STAGING), getHubConfig().getDbName(DatabaseKind.FINAL)).forEach(databaseKind -> {
            verifyIndexes(databaseKind);
        });
    }

    private void verifyIndexes(String dbName) {
        String json = new DatabaseManager(getHubClient().getManageClient()).getPropertiesAsJson(dbName);
        Database db = new DefaultResourceMapper(new API(getHubClient().getManageClient())).readResource(json, Database.class);

        List<PathNamespace> pathNamespaces = db.getPathNamespace();
        assertEquals(2, pathNamespaces.size());
        assertEquals("ex", pathNamespaces.get(0).getPrefix(), "The existing namespace is expected to be first, as the model-based " +
                "properties are expected to be merged on top of the existing properties");
        assertEquals("http://example.org", pathNamespaces.get(0).getNamespaceUri());
        assertEquals("es", pathNamespaces.get(1).getPrefix());
        assertEquals("http://marklogic.com/entity-services", pathNamespaces.get(1).getNamespaceUri());

        List<ElementIndex> rangeIndexes = db.getRangeElementIndex();
        assertEquals(2, rangeIndexes.size());
        assertEquals("testRangeIndexForDHFPROD4704", rangeIndexes.get(0).getLocalname());
        assertEquals("someProperty", rangeIndexes.get(1).getLocalname());

        List<PathIndex> pathIndexes = db.getRangePathIndex();
        assertEquals(2, pathIndexes.size());
        assertEquals("//*:instance/testPathIndexForDHFPROD4704", pathIndexes.get(0).getPathExpression());
        assertEquals("//*:instance/Customer/someOtherProperty", pathIndexes.get(1).getPathExpression());
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
