/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */

package org.dspace.app.util;

import java.sql.SQLException;
import java.time.Instant;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.dspace.app.util.factory.UtilServiceFactory;
import org.dspace.app.util.service.WebAppService;
import org.dspace.core.Context;
import org.dspace.services.ConfigurationService;
import org.dspace.services.factory.DSpaceServicesFactory;

/**
 * Represent a DSpace application while it is running.  This helps us report
 * which applications *are* running, by exposing a record that can be viewed
 * externally.
 *
 * @author mwood
 */
abstract public class AbstractDSpaceWebapp
    implements DSpaceWebappMXBean {
    private static final Logger log = LogManager.getLogger();

    protected final WebAppService webAppService = UtilServiceFactory.getInstance().getWebAppService();

    protected String kind;

    protected Instant started;

    protected String url;

    protected WebApp webApp;

    /**
     * Prevent null instantiation.
     */
    protected AbstractDSpaceWebapp() {
    }

    /**
     * Construct a particular kind of DSpace application.
     *
     * @param kind what kind of application is this?
     */
    public AbstractDSpaceWebapp(String kind) {
        this.kind = kind;

        started = Instant.now();

        ConfigurationService configurationService
                = DSpaceServicesFactory.getInstance().getConfigurationService();
        url = configurationService.getProperty("dspace.ui.url");
        if (null == url) {
            throw new IllegalStateException("dspace.ui.url is undefined");
        }
    }

    /**
     * Record that this application is running.
     */
    public void register() {
        // Create the database entry
        try {
            Context context = new Context();
            webApp = webAppService.create(context, kind, url, started, isUI() ? 1 : 0);
            context.complete();
        } catch (SQLException e) {
            log.error("Failed to record startup in Webapp table.", e);
        }
    }

    /**
     * Record that this application is not running.
     */
    public void deregister() {
        // Remove the database entry
        try {
            Context context = new Context();
            webAppService.delete(context, webApp);
            context.complete();
        } catch (SQLException e) {
            log.error("Failed to record shutdown in Webapp table.", e);
        }
    }

    @Override
    public String getKind() {
        return kind;
    }

    @Override
    public String getURL() {
        return url;
    }

    @Override
    public String getStarted() {
        return started.toString();
    }
}
