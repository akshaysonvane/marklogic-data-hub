package com.marklogic.hub.dhs.installer.deploy;

import com.marklogic.appdeployer.command.CommandContext;
import com.marklogic.appdeployer.command.appservers.DeployOtherServersCommand;

import java.io.File;

/**
 * DHS-specific command that whitelists the properties that DHF can update. This is to ensure that DHF doesn't
 * accidentally change e.g. a port number or the authentication mechanism.
 */
public class DhsDeployServersCommand extends DeployOtherServersCommand {

    @Override
    protected String adjustPayloadBeforeSavingResource(CommandContext context, File f, String payload) {
        final String[] originalIncludeProperties = context.getAppConfig().getIncludeProperties();
        context.getAppConfig().setIncludeProperties("server-name", "error-handler", "url-rewriter");
        try {
            return super.adjustPayloadBeforeSavingResource(context, f, payload);
        } finally {
            context.getAppConfig().setIncludeProperties(originalIncludeProperties);
        }
    }
}
