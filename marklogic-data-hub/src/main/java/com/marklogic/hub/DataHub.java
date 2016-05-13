/*
 * Copyright 2012-2016 MarkLogic Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.marklogic.hub;

import com.fasterxml.jackson.databind.JsonNode;
import com.marklogic.appdeployer.AppConfig;
import com.marklogic.appdeployer.ConfigDir;
import com.marklogic.appdeployer.command.Command;
import com.marklogic.appdeployer.command.alert.DeployAlertActionsCommand;
import com.marklogic.appdeployer.command.alert.DeployAlertConfigsCommand;
import com.marklogic.appdeployer.command.alert.DeployAlertRulesCommand;
import com.marklogic.appdeployer.command.appservers.DeployOtherServersCommand;
import com.marklogic.appdeployer.command.cpf.DeployCpfConfigsCommand;
import com.marklogic.appdeployer.command.cpf.DeployDomainsCommand;
import com.marklogic.appdeployer.command.cpf.DeployPipelinesCommand;
import com.marklogic.appdeployer.command.databases.DeployOtherDatabasesCommand;
import com.marklogic.appdeployer.command.databases.DeploySchemasDatabaseCommand;
import com.marklogic.appdeployer.command.databases.DeployTriggersDatabaseCommand;
import com.marklogic.appdeployer.command.flexrep.DeployConfigsCommand;
import com.marklogic.appdeployer.command.flexrep.DeployFlexrepCommand;
import com.marklogic.appdeployer.command.flexrep.DeployTargetsCommand;
import com.marklogic.appdeployer.command.forests.ConfigureForestReplicasCommand;
import com.marklogic.appdeployer.command.groups.DeployGroupsCommand;
import com.marklogic.appdeployer.command.mimetypes.DeployMimetypesCommand;
import com.marklogic.appdeployer.command.schemas.LoadSchemasCommand;
import com.marklogic.appdeployer.command.security.*;
import com.marklogic.appdeployer.command.tasks.DeployScheduledTasksCommand;
import com.marklogic.appdeployer.command.triggers.DeployTriggersCommand;
import com.marklogic.appdeployer.command.viewschemas.DeployViewSchemasCommand;
import com.marklogic.appdeployer.impl.SimpleAppDeployer;
import com.marklogic.client.DatabaseClient;
import com.marklogic.client.DatabaseClientFactory;
import com.marklogic.client.extensions.ResourceManager;
import com.marklogic.client.extensions.ResourceServices.ServiceResult;
import com.marklogic.client.extensions.ResourceServices.ServiceResultIterator;
import com.marklogic.client.io.JacksonHandle;
import com.marklogic.client.modulesloader.impl.PropertiesModuleManager;
import com.marklogic.client.util.RequestParameters;
import com.marklogic.hub.commands.LoadHubModulesCommand;
import com.marklogic.hub.commands.LoadUserModulesCommand;
import com.marklogic.hub.util.PerformanceLogger;
import com.marklogic.mgmt.ManageClient;
import com.marklogic.mgmt.ManageConfig;
import com.marklogic.mgmt.admin.AdminConfig;
import com.marklogic.mgmt.admin.AdminManager;
import com.marklogic.mgmt.appservers.ServerManager;
import com.marklogic.mgmt.databases.DatabaseManager;
import com.marklogic.rest.util.Fragment;
import com.marklogic.rest.util.ResourcesFragment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.ResourceAccessException;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DataHub {

    static final private Logger LOGGER = LoggerFactory.getLogger(DataHub.class);

    private ManageConfig config;
    private ManageClient client;

    private HubConfig hubConfig;

    public DataHub(HubConfig hubConfig) {
        init(hubConfig);
    }

    public DataHub(String host, String username, String password) {
        hubConfig = new HubConfig();
        hubConfig.host = host;
        hubConfig.adminUsername = username;
        hubConfig.adminPassword = password;
        init(hubConfig);
    }

    private void init(HubConfig hubConfig) {
        this.hubConfig = hubConfig;
        config = new ManageConfig(hubConfig.host, 8002, hubConfig.adminUsername, hubConfig.adminPassword);
        client = new ManageClient(config);
    }

    /**
     * Determines if the data hub is installed in MarkLogic
     * @return true if installed, false otherwise
     */
    public boolean isInstalled() {
        long startTime = PerformanceLogger.monitorTimeInsideMethod();

        ServerManager sm = new ServerManager(client);
        DatabaseManager dm = new DatabaseManager(client);

        ResourcesFragment srf = sm.getAsXml();
        boolean stagingAppServerExists = srf.resourceExists(hubConfig.stagingHttpName);
        boolean finalAppServerExists = srf.resourceExists(hubConfig.finalHttpName);
        boolean tracingAppServerExists = srf.resourceExists(hubConfig.tracingHttpName);
        boolean appserversOk = (stagingAppServerExists && finalAppServerExists && tracingAppServerExists);

        ResourcesFragment drf = dm.getAsXml();
        boolean stagingDbExists = drf.resourceExists(hubConfig.stagingDbName);
        boolean finalDbExists = drf.resourceExists(hubConfig.finalDbName);
        boolean tracingDbExists = drf.resourceExists(hubConfig.stagingDbName);

        boolean stagingForestsExist = false;
        boolean finalForestsExist = false;
        boolean tracingForestsExist = false;

        boolean stagingIndexesOn = false;
        boolean finalIndexesOn = false;
        boolean tracingIndexesOn = false;

        if (stagingDbExists) {
            Fragment f = dm.getPropertiesAsXml(hubConfig.stagingDbName);
            stagingIndexesOn = Boolean.parseBoolean(f.getElementValue("//m:triple-index"));
            stagingIndexesOn = stagingIndexesOn && Boolean.parseBoolean(f.getElementValue("//m:collection-lexicon"));
            stagingForestsExist = (f.getElements("//m:forest").size() == hubConfig.stagingForestsPerHost);
        }

        if (finalDbExists) {
            Fragment f = dm.getPropertiesAsXml(hubConfig.finalDbName);
            finalIndexesOn = Boolean.parseBoolean(f.getElementValue("//m:triple-index"));
            finalIndexesOn = finalIndexesOn && Boolean.parseBoolean(f.getElementValue("//m:collection-lexicon"));
            finalForestsExist = (f.getElements("//m:forest").size() == hubConfig.finalForestsPerHost);
        }

        if (tracingDbExists) {
            tracingIndexesOn = true;
            Fragment f = dm.getPropertiesAsXml(hubConfig.tracingDbName);
            tracingForestsExist = (f.getElements("//m:forest").size() == hubConfig.tracingForestsPerHost);
        }

        boolean dbsOk = (stagingDbExists && stagingIndexesOn &&
                finalDbExists && finalIndexesOn &&
                tracingDbExists && tracingIndexesOn);
        boolean forestsOk = (stagingForestsExist && finalForestsExist && tracingForestsExist);

        PerformanceLogger.logTimeInsideMethod(startTime, "DataHub.isInstalled");

        return (appserversOk && dbsOk && forestsOk);
    }

    /**
     * Validates the MarkLogic server to ensure compatibility with the hub
     * @throws ServerValidationException if the server is not compatible
     */
    public void validateServer() throws ServerValidationException {
        long startTime = PerformanceLogger.monitorTimeInsideMethod();
        try {
            AdminManager am = getAdminManager();
            String versionString = am.getServerVersion();
            int major = Integer.parseInt(versionString.substring(0, 1));
            int minor = Integer.parseInt(versionString.substring(2, 3) + versionString.substring(4, 5));
            if (major < 8 || minor < 4) {
                throw new ServerValidationException("Invalid MarkLogic Server Version: " + versionString);
            }
        }
        catch(ResourceAccessException e) {
            throw new ServerValidationException(e.toString());
        }
        PerformanceLogger.logTimeInsideMethod(startTime, "DataHub.validateServer");
    }

    private AppConfig getAppConfig() {
        AppConfig config = new AppConfig();
        updateAppConfig(config);
        return config;
    }

    public void updateAppConfig(AppConfig config) {
        config.setHost(hubConfig.host);
        config.setRestPort(hubConfig.stagingPort);
        config.setName(hubConfig.name);
        config.setRestAdminUsername(hubConfig.adminUsername);
        config.setRestAdminPassword(hubConfig.adminPassword);
        config.setModulesDatabaseName(hubConfig.modulesDbName);

        config.setTriggersDatabaseName(hubConfig.triggersDbName);
        config.setSchemasDatabaseName(hubConfig.schemasDbName);
        config.setModulesDatabaseName(hubConfig.modulesDbName);

        config.setReplaceTokensInModules(true);
        config.setUseRoxyTokenPrefix(false);

        HashMap<String, Integer> forestCounts = new HashMap<String, Integer>();
        forestCounts.put(hubConfig.stagingDbName, hubConfig.stagingForestsPerHost);
        forestCounts.put(hubConfig.finalDbName, hubConfig.finalForestsPerHost);
        forestCounts.put(hubConfig.tracingDbName, hubConfig.tracingForestsPerHost);
        forestCounts.put(hubConfig.modulesDbName, 1);
        config.setForestCounts(forestCounts);

        ConfigDir configDir = new ConfigDir(Paths.get(hubConfig.projectDir, "config").toFile());
        config.setConfigDir(configDir);

        Map<String, String> customTokens = config.getCustomTokens();

        customTokens.put("%%STAGING_SERVER_NAME%%", hubConfig.stagingHttpName);
        customTokens.put("%%STAGING_SERVER_PORT%%", hubConfig.stagingPort.toString());
        customTokens.put("%%STAGING_DB_NAME%%", hubConfig.stagingDbName);
        customTokens.put("%%STAGING_FORESTS_PER_HOST%%", hubConfig.stagingForestsPerHost.toString());

        customTokens.put("%%FINAL_SERVER_NAME%%", hubConfig.finalHttpName);
        customTokens.put("%%FINAL_SERVER_PORT%%", hubConfig.finalPort.toString());
        customTokens.put("%%FINAL_DB_NAME%%", hubConfig.finalDbName);
        customTokens.put("%%FINAL_FORESTS_PER_HOST%%", hubConfig.finalForestsPerHost.toString());

        customTokens.put("%%TRACE_SERVER_NAME%%", hubConfig.tracingHttpName);
        customTokens.put("%%TRACE_SERVER_PORT%%", hubConfig.tracePort.toString());
        customTokens.put("%%TRACE_DB_NAME%%", hubConfig.tracingDbName);
        customTokens.put("%%TRACE_FORESTS_PER_HOST%%", hubConfig.tracingForestsPerHost.toString());

        customTokens.put("%%MODULES_DB_NAME%%", hubConfig.modulesDbName);
    }

    public void initProject() {
        long startTime = PerformanceLogger.monitorTimeInsideMethod();
        LOGGER.info("Initializing the Hub Project");

        HubProject hp = new HubProject(hubConfig);
        hp.init();

        PerformanceLogger.logTimeInsideMethod(startTime, "DataHub.initProject");
    }

    private DatabaseClient getDatabaseClient(int port) {
        AppConfig config = new AppConfig();
        config.setHost(hubConfig.host);
        config.setName(hubConfig.name);
        config.setRestAdminUsername(hubConfig.adminUsername);
        config.setRestAdminPassword(hubConfig.adminPassword);
        DatabaseClient client = DatabaseClientFactory.newClient(hubConfig.host, port, hubConfig.adminUsername, hubConfig.adminPassword,
                config.getRestAuthentication(), config.getRestSslContext(), config.getRestSslHostnameVerifier());
        return client;
    }

    /**
     * Installs User Provided modules into the Data Hub
     */
    public void installUserModules() {
        long startTime = PerformanceLogger.monitorTimeInsideMethod();
        LOGGER.debug("Installing user modules into MarkLogic");

        List<Command> commands = new ArrayList<Command>();
        commands.add(new LoadUserModulesCommand(hubConfig));

        AppConfig config = getAppConfig();
        SimpleAppDeployer deployer = new SimpleAppDeployer(client, getAdminManager());
        deployer.setCommands(commands);
        deployer.deploy(config);
        PerformanceLogger.logTimeInsideMethod(startTime, "DataHub.installUserModules");
    }

    public JsonNode validateUserModules() {
        long startTime = PerformanceLogger.monitorTimeInsideMethod();
        LOGGER.debug("validating user modules");

        DatabaseClient client = getDatabaseClient(hubConfig.stagingPort);
        EntitiesValidator ev = new EntitiesValidator(client);
        JsonNode jsonNode = ev.validate();

        PerformanceLogger.logTimeInsideMethod(startTime, "DataHub.validateUserModules");

        return jsonNode;
    }

    private List<Command> getCommands(AppConfig config) {
        List<Command> commands = new ArrayList<Command>();

        // Security
        List<Command> securityCommands = new ArrayList<Command>();
        securityCommands.add(new DeployRolesCommand());
        securityCommands.add(new DeployUsersCommand());
        securityCommands.add(new DeployAmpsCommand());
        securityCommands.add(new DeployCertificateTemplatesCommand());
        securityCommands.add(new DeployCertificateAuthoritiesCommand());
        securityCommands.add(new DeployExternalSecurityCommand());
        securityCommands.add(new DeployPrivilegesCommand());
        securityCommands.add(new DeployProtectedCollectionsCommand());
        commands.addAll(securityCommands);

        // Databases
        List<Command> dbCommands = new ArrayList<>();
        dbCommands.add(new DeployOtherDatabasesCommand());
        dbCommands.add(new DeployTriggersDatabaseCommand());
        dbCommands.add(new DeploySchemasDatabaseCommand());
        commands.addAll(dbCommands);

        // Schemas
        LoadSchemasCommand lsc = new LoadSchemasCommand();
        commands.add(lsc);

        // App servers
        commands.add(new DeployOtherServersCommand());

        // Modules
        commands.add(new LoadHubModulesCommand());

        // Alerting
        List<Command> alertCommands = new ArrayList<Command>();
        alertCommands.add(new DeployAlertConfigsCommand());
        alertCommands.add(new DeployAlertActionsCommand());
        alertCommands.add(new DeployAlertRulesCommand());
        commands.addAll(alertCommands);

        // CPF
        List<Command> cpfCommands = new ArrayList<Command>();
        cpfCommands.add(new DeployCpfConfigsCommand());
        cpfCommands.add(new DeployDomainsCommand());
        cpfCommands.add(new DeployPipelinesCommand());
        commands.addAll(cpfCommands);

        // Flexrep
        List<Command> flexrepCommands = new ArrayList<Command>();
        flexrepCommands.add(new DeployConfigsCommand());
        flexrepCommands.add(new DeployTargetsCommand());
        flexrepCommands.add(new DeployFlexrepCommand());
        commands.addAll(flexrepCommands);

        // Groups
        List<Command> groupCommands = new ArrayList<Command>();
        groupCommands.add(new DeployGroupsCommand());
        commands.addAll(groupCommands);

        List<Command> mimetypeCommands = new ArrayList<Command>();
        mimetypeCommands.add(new DeployMimetypesCommand());
        commands.addAll(mimetypeCommands);

        // Forest replicas
        List<Command> replicaCommands = new ArrayList<Command>();
        replicaCommands.add(new ConfigureForestReplicasCommand());
        commands.addAll(replicaCommands);

        // Tasks
        List<Command> taskCommands = new ArrayList<Command>();
        taskCommands.add(new DeployScheduledTasksCommand());
        commands.addAll(taskCommands);

        // Triggers
        List<Command> triggerCommands = new ArrayList<Command>();
        triggerCommands.add(new DeployTriggersCommand());
        commands.addAll(triggerCommands);

        // SQL Views
        List<Command> viewCommands = new ArrayList<Command>();
        viewCommands.add(new DeployViewSchemasCommand());
        commands.addAll(viewCommands);

        return commands;
    }

    private AdminManager getAdminManager() {
        AdminConfig adminConfig = new AdminConfig();
        adminConfig.setHost(hubConfig.host);
        adminConfig.setUsername(hubConfig.adminUsername);
        adminConfig.setPassword(hubConfig.adminPassword);
        AdminManager manager = new AdminManager(adminConfig);
        return manager;
    }

    /**
     * Installs the data hub configuration and server-side modules into MarkLogic
     */
    public void install() {
        initProject();

        long startTime = PerformanceLogger.monitorTimeInsideMethod();
        LOGGER.info("Installing the Data Hub into MarkLogic");

        // clean up any lingering cache for deployed modules
        PropertiesModuleManager moduleManager = new PropertiesModuleManager();
        moduleManager.deletePropertiesFile();

        AppConfig config = getAppConfig();
        SimpleAppDeployer deployer = new SimpleAppDeployer(client, getAdminManager());
        deployer.setCommands(getCommands(config));
        deployer.deploy(config);

        PerformanceLogger.logTimeInsideMethod(startTime, "DataHub.install");
    }

    /**
     * Uninstalls the data hub configuration and server-side modules from MarkLogic
     */
    public void uninstall() {
        long startTime = PerformanceLogger.monitorTimeInsideMethod();
        LOGGER.debug("Uninstalling the Data Hub from MarkLogic");

        AppConfig config = getAppConfig();
        SimpleAppDeployer deployer = new SimpleAppDeployer(client, getAdminManager());
        deployer.setCommands(getCommands(config));
        deployer.undeploy(config);

        // clean up any lingering cache for deployed modules
        PropertiesModuleManager moduleManager = new PropertiesModuleManager();
        moduleManager.deletePropertiesFile();

        PerformanceLogger.logTimeInsideMethod(startTime, "DataHub.uninstall");
    }

    class EntitiesValidator extends ResourceManager {
        private static final String NAME = "validate";

        public EntitiesValidator(DatabaseClient client) {
            super();
            client.init(NAME, this);
        }

        public JsonNode validate() {
            RequestParameters params = new RequestParameters();
            ServiceResultIterator resultItr = this.getServices().get(params);
            if (resultItr == null || ! resultItr.hasNext()) {
                return null;
            }
            ServiceResult res = resultItr.next();
            JacksonHandle handle = new JacksonHandle();
            return res.getContent(handle).get();
        }
    }
}
