package com.marklogic.hub.dhs.installer.command;

import com.beust.jcommander.Parameters;
import com.marklogic.client.DatabaseClient;
import com.marklogic.client.FailedRequestException;
import com.marklogic.client.document.GenericDocumentManager;
import com.marklogic.client.io.DocumentMetadataHandle;
import com.marklogic.hub.DatabaseKind;
import com.marklogic.hub.dhs.installer.Options;
import com.marklogic.mgmt.resource.security.RoleManager;
import com.marklogic.mgmt.resource.security.UserManager;
import com.marklogic.rest.util.ResourcesFragment;
import org.springframework.context.ApplicationContext;

import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

@Parameters(commandDescription = "Verify a DHF installation in a DHS environment")
public class VerifyDhfInDhsCommand extends AbstractVerifyCommand {

    private static final String CURATOR = "Curator";
    private static final String EVALUATOR = "Evaluator";

    @Override
    public void run(ApplicationContext context, Options options) {
        // TODO This is hacky, need a better mechanism for building these properties post 5.0.2
        Properties props = new InstallIntoDhsCommand().buildDefaultProjectProperties(options);
        initializeProject(context, options, props);

        long start = System.currentTimeMillis();

        verifyCertainDhfRolesNotCreated();
        verifyDhfUsersNotCreated();
        verifyPrivileges();
        verifyAmps();

        verifyStagingDatabase();
        verifyFinalDatabase();
        verifyJobDatabase();

        verifyTriggers();

        verifyStagingServers();
        verifyFinalServers();

        verifyModules();
        verifyArtifacts();

        logger.info("Time to verify: " + (System.currentTimeMillis() - start));
    }

    private void verifyDhfUsersNotCreated() {
        ResourcesFragment users = new UserManager(hubConfig.getManageClient()).getAsXml();
        for (String user : getDhfUserNames()) {
            verify(!users.resourceExists(user), "Expected DHF user to not be created since DHS manages users: " + user);
        }
    }

    private void verifyCertainDhfRolesNotCreated() {
        ResourcesFragment roles = new RoleManager(hubConfig.getManageClient()).getAsXml();

        Stream.of("flow-developer-role", "flow-operator-role", "data-hub-admin-role").forEach(role -> {
            verify(!roles.resourceExists(role), "As of 5.2.0, the 3 'legacy' roles in DHF should not be deployed; found role: " + role);
        });

        // Spot check a few of the roles that should be there
        Stream.of("data-hub-developer", "data-hub-operator", "data-hub-flow-reader").forEach(role -> {
            verify(roles.resourceExists(role), "As of 5.2.0, each of the new DHF roles should be deployed to DHS; did not find role: " + role);
        });
    }

    private void verifyStagingServers() {
        verifyStagingServer(CURATOR);
        verifyStagingServer(EVALUATOR);
    }

    private void verifyFinalServers() {
        verifyFinalServer(CURATOR);
        verifyFinalServer(EVALUATOR);
    }

    private void verifyModules() {
        final int finalPort = hubConfig.getPort(DatabaseKind.FINAL);
        // Use the staging port to verify the modules that have been loaded, as that port is also used for loading
        // non-REST modules and thus should be accessible
        hubConfig.setPort(DatabaseKind.FINAL, hubConfig.getPort(DatabaseKind.STAGING));
        DatabaseClient client = hubConfig.newModulesDbClient();

        try {
            GenericDocumentManager documentManager = client.newDocumentManager();

            DocumentMetadataHandle metadata = documentManager.readMetadata("/com.marklogic.hub/config.sjs", new DocumentMetadataHandle());
            DocumentMetadataHandle.DocumentPermissions perms = metadata.getPermissions();

            Set<DocumentMetadataHandle.Capability> capabilities = perms.get("data-hub-module-reader");
            verify(capabilities.contains(DocumentMetadataHandle.Capability.READ), "Reader should be able to read modules");
            verify(!capabilities.contains(DocumentMetadataHandle.Capability.INSERT), "Reader should not be able to insert modules");
            verify(!capabilities.contains(DocumentMetadataHandle.Capability.UPDATE), "Reader should not be able to update modules");
            verify(capabilities.contains(DocumentMetadataHandle.Capability.EXECUTE), "Reader should be able to execute modules");

            capabilities = perms.get("data-hub-environment-manager");
            verify(capabilities.contains(DocumentMetadataHandle.Capability.UPDATE), "Only data-hub-environment-manager should be able to update DHF core modules");

            capabilities = perms.get("rest-extension-user");
            verify(capabilities.contains(DocumentMetadataHandle.Capability.EXECUTE), "A rest-extension-user/execute permission is included for consistency with REST extensions");

            verifyOptionsExist(documentManager,
                "/Evaluator/data-hub-JOBS/rest-api/options/jobs.xml",
                "/Evaluator/data-hub-JOBS/rest-api/options/traces.xml",
                "/Evaluator/data-hub-STAGING/rest-api/options/default.xml",
                "/Evaluator/data-hub-FINAL/rest-api/options/default.xml",
                "/Curator/data-hub-FINAL/rest-api/options/default.xml",
                "/Curator/data-hub-JOBS/rest-api/options/jobs.xml",
                "/Curator/data-hub-JOBS/rest-api/options/traces.xml",
                "/Curator/data-hub-STAGING/rest-api/options/default.xml",
                "/Analyzer/data-hub-ANALYTICS/rest-api/options/default.xml",
                "/Analyzer/data-hub-ANALYTICS-REST/rest-api/options/default.xml",
                "/Operator/data-hub-OPERATION/rest-api/options/default.xml",
                "/Operator/data-hub-OPERATION-REST/rest-api/options/default.xml",
                "/Evaluator/data-hub-ANALYTICS/rest-api/options/default.xml",
                "/Evaluator/data-hub-OPERATION/rest-api/options/default.xml"
            );
        } finally {
            hubConfig.setPort(DatabaseKind.FINAL, finalPort);
            client.release();
        }
    }

    protected void verifyOptionsExist(GenericDocumentManager documentManager, String... expectedOptions) {
        for (String options : expectedOptions) {
            try {
                verify(documentManager.exists(options) != null, "Expected options module to exist: " + options);
            } catch (FailedRequestException ex) {
                throw new RuntimeException("Unable to find options module: " + options);
            }
        }
    }
}
